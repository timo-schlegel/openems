package io.openems.edge.rctpower.bridge.api.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.rctpower.bridge.api.AbstractRctPowerBridge;
import io.openems.edge.rctpower.bridge.api.RctFrame;
import io.openems.edge.rctpower.bridge.api.RctReadSkippedException;
import io.openems.edge.rctpower.bridge.api.RctRequest;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.task.ReadObjectTask;
import io.openems.edge.rctpower.bridge.api.task.ReadTask;
import io.openems.edge.rctpower.bridge.api.task.Task.ExecuteState;
import io.openems.edge.rctpower.bridge.api.worker.RctReadWorker.ResponseSource;

public class ReadBatchExecutor {

	private final Logger log = LoggerFactory.getLogger(ReadBatchExecutor.class);
	
	public record BatchTaskResult(
	        ReadTask task,
	        ExecuteState state,
	        long durationNanos,
	        RctReadWorker.ResponseEntry responseEntry) {
	}

	public record BatchExecutionResult(
	        List<BatchTaskResult> taskResults,
	        int expectedResponses,
	        int receivedResponses,
	        int deviceResponses,
	        boolean usedFallback,
	        boolean hasMissingResponses,
	        int cachedResponses,
	        long maxCacheCycleAge) {

		public boolean isPartial() {
			return this.hasMissingResponses;
		}
	}
	
	public BatchExecutionResult execute(AbstractRctPowerBridge bridge, List<ReadTask> tasks, int attemptTimeoutMs) {
		long startNanos = System.nanoTime();
		List<BatchTaskResult> results = new ArrayList<>();

		try {
			List<ReadObjectTask> delegates = tasks.stream()
					.filter(ReadObjectTask.class::isInstance)
					.map(ReadObjectTask.class::cast)
					.toList();

			List<RctRequest> requests = delegates.stream()
					.map(t -> t.getReadObject().createRequest())
					.toList();

			var tx = bridge.getNewRctBatchTransaction(requests);
			tx.setAttemptTimeoutMs(attemptTimeoutMs);

			//this.log.info("TEMP batch tx start: "
			//        + "taskCount=" + delegates.size()
			//        + " attemptTimeoutMs=" + attemptTimeoutMs);

			var batchResult = tx.execute();

			Map<Integer, RctReadWorker.ResponseEntry> responses = batchResult.responses();

			int cachedResponses = (int) responses.values().stream()
					.filter(e -> e.source() == ResponseSource.CACHE)
					.count();

			int deviceResponses = (int) responses.values().stream()
			        .filter(e -> e.source() == ResponseSource.DEVICE)
			        .count();

			long maxCacheCycleAge = responses.values().stream()
					.filter(e -> e.source() == ResponseSource.CACHE)
					.mapToLong(RctReadWorker.ResponseEntry::cycleAge)
					.max()
					.orElse(0);

			//this.log.info("TEMP batch responses received: count=" + responses.size()
	        //+ " usedFallback=" + batchResult.usedFallback()
	        //+ " missing=" + batchResult.missingOids());

			long totalDurationNanos = System.nanoTime() - startNanos;
			long perTaskDurationNanos = delegates.isEmpty() ? 0 : totalDurationNanos / delegates.size();

			for (var task : delegates) {
				try {
					//this.log.info("TEMP batch response lookup: "
					//        + "taskOid=0x" + Integer.toHexString(task.getReadObject().getOid())
					//        + " found=" + responses.containsKey(task.getReadObject().getOid()));

					var entry = responses.get(task.getReadObject().getOid());
					if (entry == null) {
					    var skipped = new RctReadSkippedException(
					            "Missing response for oid [" + task.getReadObject().getOid() + "]",
					            new java.net.SocketTimeoutException("Missing response in batch read"));

					    results.add(new BatchTaskResult(
					            task,
					            task.executeBatchSkipped(bridge, skipped, perTaskDurationNanos),
					            perTaskDurationNanos,
					            null));
					    continue;
					}

					var response = (RctResponse) entry.cachedFrame().frame();

					results.add(new BatchTaskResult(
					        task,
					        task.executeBatchResponse(bridge, response, entry, perTaskDurationNanos),
					        perTaskDurationNanos,
					        entry));

				} catch (Exception e) {
					var skipped = new RctReadSkippedException(
							"Batch read failed: " + e.getMessage(),
							e);
					
					results.add(new BatchTaskResult(task, task.executeBatchSkipped(bridge, skipped, perTaskDurationNanos), perTaskDurationNanos, null));
				}
			}

			return new BatchExecutionResult(
			        results,
			        delegates.size(),
			        responses.size(),
			        deviceResponses,
			        batchResult.usedFallback(),
			        !batchResult.missingOids().isEmpty(),
			        cachedResponses,
			        maxCacheCycleAge);

		} catch (RctReadSkippedException e) {
			long totalDurationNanos = System.nanoTime() - startNanos;
			long perTaskDurationNanos = tasks.isEmpty() ? 0 : totalDurationNanos / tasks.size();

		    this.log.info("TEMP batch tx skipped: "
		            + "taskCount=" + tasks.size()
		            + " attemptTimeoutMs=" + attemptTimeoutMs
		            + " durationMs=" + (totalDurationNanos / 1_000_000L)
		            + " message=" + e.getMessage());

			for (var task : tasks) {
				results.add(new BatchTaskResult(
						task,
						task.executeBatchSkipped(bridge, e, perTaskDurationNanos),
						perTaskDurationNanos,
						null));
			}

			return new BatchExecutionResult(
					results,
					tasks.size(),
					0,
					0, // deviceResponses
					false, // usedFallback
					true,  // hasMissingResponses
					0, // cachedResponses
					0  // maxCacheCycleAge
			);

		} catch (Exception e) {
			long totalDurationNanos = System.nanoTime() - startNanos;
			long perTaskDurationNanos = tasks.isEmpty() ? 0 : totalDurationNanos / tasks.size();

		    this.log.info("TEMP batch tx failed: "
		            + "taskCount=" + tasks.size()
		            + " attemptTimeoutMs=" + attemptTimeoutMs
		            + " durationMs=" + (totalDurationNanos / 1_000_000L)
		            + " message=" + e.getMessage());

			for (var task : tasks) {
				results.add(new BatchTaskResult(task, new ExecuteState.Error(e), perTaskDurationNanos, null));
			}

			return new BatchExecutionResult(
					results,
					tasks.size(),
					0,
					0, // deviceResponses
					false, // usedFallback
					true,  // hasMissingResponses
					0, // cachedResponses
					0  // maxCacheCycleAge
			);
		}

	}
}