package io.openems.edge.rctpower.bridge.api;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.rctpower.bridge.api.worker.RctReadWorker;
import io.openems.edge.rctpower.bridge.api.worker.RctReadWorker.BatchReadResult;

public class RctBatchTransaction {

	private static final int DEFAULT_ATTEMPT_TIMEOUT_MS = 150;

	private final RctTCPConnection rctTCPConnection;
	private final RctTCPTransport rctIO;
	private final List<RctRequest> requests;
	private final RctReadWorker readWorker;

	private int attemptTimeoutMs = DEFAULT_ATTEMPT_TIMEOUT_MS;

	public RctBatchTransaction(RctTCPConnection con, List<RctRequest> requests, RctReadWorker readWorker) {
		this.rctTCPConnection = con;
		this.rctIO = con.getRctTCPTransport();
		this.requests = requests;
		this.readWorker = readWorker;
	}

	public void setAttemptTimeoutMs(int attemptTimeoutMs) {
		this.attemptTimeoutMs = Math.max(1, attemptTimeoutMs);
	}

	public BatchReadResult execute() throws OpenemsException {
		if (this.requests == null || this.requests.isEmpty() || this.rctTCPConnection == null) {
			throw new OpenemsException("Invalid batch request or connection");
		}

		ensureConnected();

		final Set<Integer> expectedOids = this.requests.stream()
				.map(RctFrame::getID)
				.map(RctBatchTransaction::bytesToInt)
				.collect(Collectors.toSet());

		try {
			long marker = this.readWorker.mark();

			synchronized (this.rctIO) {
				this.rctIO.writeMessages(this.requests);
			}

			var result = this.readWorker.awaitResponses(expectedOids, marker, this.attemptTimeoutMs);
			
			return result;

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new OpenemsException("Batch transaction interrupted", e);

		} catch (IOException e) {
			throw new OpenemsException("Batch transaction failed: " + e.getMessage(), e);
		}
	}

	private void ensureConnected() throws OpenemsException {
		if (!this.rctTCPConnection.isConnected()) {
			try {
				this.rctTCPConnection.ensureConnected();
			} catch (Exception ex) {
				throw new OpenemsException("Connection failed.", ex);
			}
		}
	}

	private void safelyCloseConnection() {
		try {
			this.rctTCPConnection.close();
		} catch (Exception ignore) {
			// ignore
		}
	}

	private static boolean isTimeoutLike(Throwable t) {
		while (t != null) {
			if (t instanceof java.net.SocketTimeoutException) {
				return true;
			}
			t = t.getCause();
		}
		return false;
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
}