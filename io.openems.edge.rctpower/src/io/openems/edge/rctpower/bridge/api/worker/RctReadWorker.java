package io.openems.edge.rctpower.bridge.api.worker;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import io.openems.common.worker.AbstractImmediateWorker;
import io.openems.edge.rctpower.bridge.api.RctFrame;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.RctTCPTransport;

public class RctReadWorker extends AbstractImmediateWorker {

	private final Supplier<RctTCPTransport> transportSupplier;

	private final AtomicLong sequence = new AtomicLong();
	private volatile long currentCycleId = 0;

	private final Object monitor = new Object();

	private final Map<Integer, CachedFrame> latestByOid = new ConcurrentHashMap<>();

	private final Map<Integer, Long> lastUsedSequenceByOid = new ConcurrentHashMap<>();

	public RctReadWorker(Supplier<RctTCPTransport> transportSupplier) {
		this.transportSupplier = transportSupplier;
	}

	@Override
	protected void forever() throws InterruptedException {
		try {
			/*
			 * Important:
			 * This method must read from the TCP stream continuously.
			 * It should block until at least one complete frame is available.
			 *
			 * Prefer a buffered implementation in RctTCPTransport:
			 * - read available bytes into an internal buffer
			 * - parse 0..n complete frames
			 * - keep incomplete tail bytes for the next call
			 * - discard CRC-invalid/unsupported frames and resync
			 */
			var transport = this.transportSupplier.get();
			if (transport == null) {
				Thread.sleep(100);
				return;
			}

			var frames = transport.readAvailableFramesBlocking();

			if (frames.isEmpty()) {
				return;
			}

			for (var frame : frames) {
				this.accept(frame);
			}

		} catch (IOException e) {
			/*
			 * Do not mark tasks defective here.
			 * The transaction side decides if no fresh/cached response is available.
			 */
			Thread.sleep(100);
		}
	}

	private void accept(RctFrame frame) {
	    if (!(frame instanceof RctResponse)) {
	        return;
	    }

	    if (!isSupportedResponseCommand(frame.getCommand())) {
	        return;
	    }

	    var oid = bytesToInt(frame.getID());
	    var cached = new CachedFrame(
	            oid,
	            frame,
	            this.sequence.incrementAndGet(),
	            this.currentCycleId,
	            System.nanoTime(),
	            Instant.now());

	    this.latestByOid.put(oid, cached);

	    synchronized (this.monitor) {
	        this.monitor.notifyAll();
	    }
	}

	private static boolean isSupportedResponseCommand(byte command) {
	    return command == 0x05 || command == 0x06;
	}

	public long mark() {
		return this.sequence.get();
	}

	public BatchReadResult awaitResponses(Collection<Integer> expectedOids, long marker, long timeoutMs)
			throws InterruptedException {

		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		Map<Integer, CachedFrame> fresh = new LinkedHashMap<>();

		while (System.nanoTime() < deadline) {
			fresh.clear();

			for (var oid : expectedOids) {
				var cached = this.latestByOid.get(oid);
				if (cached != null && cached.sequence() > marker) {
					fresh.put(oid, cached);
				}
			}

			if (fresh.size() == expectedOids.size()) {
				this.rememberUsedResponses(fresh);
				return BatchReadResult.success(fresh);
			}

			long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
			if (remainingMs <= 0) {
				break;
			}

			synchronized (this.monitor) {
				this.monitor.wait(Math.min(remainingMs, 20));
			}
		}

		Map<Integer, ResponseEntry> result = new LinkedHashMap<>();
		Collection<Integer> missing = new ArrayList<>();

		for (var oid : expectedOids) {
			var cached = this.latestByOid.get(oid);

			if (cached != null && cached.sequence() > marker) {
				result.put(oid, new ResponseEntry(
						cached,
						ResponseSource.DEVICE,
						this.currentCycleId - cached.cycleId()));
				this.rememberUsedResponse(oid, cached);
				continue;
			}

			var fallback = this.findFallback(oid);
			if (fallback.isPresent()) {
			    var cachedFallback = fallback.get();

			    result.put(oid, new ResponseEntry(
			            cachedFallback,
			            ResponseSource.CACHE,
			            this.currentCycleId - cachedFallback.cycleId()));

			    this.rememberUsedResponse(oid, cachedFallback);
			} else {
				missing.add(oid);
			}
		}

		return new BatchReadResult(result, missing);
	}

	private void rememberUsedResponse(int oid, CachedFrame frame) {
	    this.lastUsedSequenceByOid.merge(
	            oid,
	            frame.sequence(),
	            Math::max);
	}

	private void rememberUsedResponses(Map<Integer, CachedFrame> responses) {
	    responses.forEach(this::rememberUsedResponse);
	}

	private Optional<CachedFrame> findFallback(int oid) {
		long lastUsedSequence = this.lastUsedSequenceByOid.getOrDefault(oid, Long.MIN_VALUE);

		var latest = this.latestByOid.get(oid);
		if (latest != null && latest.sequence() > lastUsedSequence) {
			return Optional.of(latest);
		}

		return Optional.empty();
	}

	public long getCurrentCycleId() {
		return this.currentCycleId;
	}

	public Optional<CachedFrame> getLatestForDebug(int oid) {
		return Optional.ofNullable(this.latestByOid.get(oid));
	}

	/**
	 * Called on BEFORE_PROCESS_IMAGE event.
	 */
	public void onBeforeProcessImage() {
		this.currentCycleId++;

		synchronized (this.monitor) {
			this.monitor.notifyAll();
		}
	}

	private static int bytesToInt(byte[] id) {
		if (id == null || id.length != 4) {
			return Integer.MIN_VALUE;
		}
		return ((id[0] & 0xFF) << 24)
				| ((id[1] & 0xFF) << 16)
				| ((id[2] & 0xFF) << 8)
				| (id[3] & 0xFF);
	}

	public record CachedFrame(
			int oid,
			RctFrame frame,
			long sequence,
			long cycleId,
			long receivedAtNanos,
			Instant receivedAt) {
	}

	public enum ResponseSource {
	    DEVICE,
	    CACHE
	}

	public record ResponseEntry(
	        CachedFrame cachedFrame,
	        ResponseSource source,
	        long cycleAge) {
	}

	public record BatchReadResult(
	        Map<Integer, ResponseEntry> responses,
	        Collection<Integer> missingOids) {

	    public static BatchReadResult success(Map<Integer, CachedFrame> fresh) {
	        Map<Integer, ResponseEntry> entries = new LinkedHashMap<>();

	        fresh.forEach((oid, cached) -> entries.put(
	                oid,
	                new ResponseEntry(cached, ResponseSource.DEVICE, 0)));

	        return new BatchReadResult(entries, java.util.List.of());
	    }

	    public boolean isComplete() {
	        return this.missingOids.isEmpty();
	    }

	    public boolean usedFallback() {
	        return this.responses.values().stream()
	                .anyMatch(e -> e.source() == ResponseSource.CACHE);
	    }
	}
}