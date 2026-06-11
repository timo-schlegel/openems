package io.openems.edge.rctpower.bridge.api.worker;

import static io.openems.common.utils.FunctionUtils.doNothing;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.worker.AbstractImmediateWorker;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.rctpower.bridge.api.RctComponent;
import io.openems.edge.rctpower.bridge.api.RctProtocol;
import io.openems.edge.rctpower.bridge.api.RctReadSkippedException;
import io.openems.edge.rctpower.bridge.api.AbstractRctPowerBridge;
import io.openems.edge.rctpower.bridge.api.Config.LogHandler;
import io.openems.edge.rctpower.bridge.api.element.RctElement;
import io.openems.edge.rctpower.bridge.api.task.ReadTask;
import io.openems.edge.rctpower.bridge.api.task.Task;
import io.openems.edge.rctpower.bridge.api.task.Task.ExecuteState;
import io.openems.edge.rctpower.bridge.api.worker.internal.CycleTasks;
import io.openems.edge.rctpower.bridge.api.worker.internal.CycleTasksManager;
import io.openems.edge.rctpower.bridge.api.worker.internal.DefectiveComponents;
import io.openems.edge.rctpower.bridge.api.worker.internal.TasksSupplierImpl;


/*
 * TODO
 * Create RctComponent und RctProtocol
 * Bruachen wir BrigeRct oder reicht da RctTCPConnection?
 * 
 */

/**
 * The RctWorker schedules the execution of all Rct-Tasks, like reading
 * and writing Rct object.
 *
 * <p>
 * It tries to execute all Write-Tasks as early as possible (directly after the
 * TOPIC_CYCLE_EXECUTE_WRITE event) and all Read-Tasks as late as possible to
 * have values available exactly when they are needed (i.e. at the
 * TOPIC_CYCLE_BEFORE_PROCESS_IMAGE event). For this it uses a
 * {@link CycleTasksManager} that internally uses a {@link TasksSupplierImpl}
 * that supplies the tasks for one Cycle ({@link CycleTasks}).
 */
public class RctWorker extends AbstractImmediateWorker {

	private static final int MAX_CONSECUTIVE_SOFT_SKIP_CYCLES = 2;
	
	private static final int MAX_BURST_ESCALATION_SUPPRESSIONS = 6;

	private static final boolean ENABLE_READ_BATCHING = true;

	// Callbacks
	private final Function<Task, ExecuteState> execute;
	private final Consumer<RctElement[]> invalidate;

	private final AbstractRctPowerBridge bridge;
	private final DefectiveComponents defectiveComponents;
	private final TasksSupplierImpl tasksSupplier;
	private final CycleTasksManager cycleTasksManager;

	private int consecutiveSoftSkipCycles = 0;

	private final AtomicLong readCycleSuccessCount = new AtomicLong();
	private final AtomicLong readCycleSoftFailCount = new AtomicLong();
	private final AtomicLong readCycleHardFailCount = new AtomicLong();
	private final AtomicLong readCycleBackpressureSkipCount = new AtomicLong();

	private volatile long skippedStatsWindowStartedMs = System.currentTimeMillis();
	private static final long SKIPPED_STATS_WINDOW_MS = 10 * 60 * 1000L;

	private final ReadBatchExecutor readBatchExecutor = new ReadBatchExecutor();

	//private boolean inReadState = false;
	//private Instant inReadStateSince = null;
	
	private final Logger log = LoggerFactory.getLogger(RctWorker.class);

	/**
	 * Constructor for {@link RctWorker}.
	 * 
	 * @param execute                    executes a {@link Task}; returns number of
	 *                                   actually executed subtasks
	 * @param invalidate                 invalidates the given
	 *                                   {@link RctObject}s after read errors
	 * @param cycleTimeIsTooShortChannel sets the
	 *                                   {@link BridgeRctPower.ChannelId#CYCLE_TIME_IS_TOO_SHORT}
	 *                                   channel
	 * @param cycleDelayChannel          sets the
	 *                                   {@link BridgeRctPower.ChannelId#CYCLE_DELAY}
	 *                                   channel
	 * @param logHandler                 a {@link Supplier} for the
	 *                                   {@link LogHandler}
	 */
	public RctWorker(AbstractRctPowerBridge bridge,
			Function<Task, ExecuteState> execute, Consumer<RctElement[]> invalidate,
			Consumer<Boolean> cycleTimeIsTooShortChannel, Consumer<Long> cycleDelayChannel,
			Supplier<LogHandler> logHandler
			) {
		this.bridge = bridge;
		this.execute = execute;
		this.invalidate = invalidate;

		this.defectiveComponents = new DefectiveComponents(logHandler);
		this.tasksSupplier = new TasksSupplierImpl(logHandler);
		this.cycleTasksManager = new CycleTasksManager(this.tasksSupplier, this.defectiveComponents,
				cycleTimeIsTooShortChannel, cycleDelayChannel, logHandler);
	}

	@Override
	protected void forever() throws InterruptedException {
		//long lastReadDuration = this.cycleTasksManager.getLastReadDuration();
		//long lastReadTasks = this.cycleTasksManager.getLastReadTasksCount();
		//long maxReadDuration = lastReadDuration > 0 ? lastReadDuration + WaitDelayHandler.BUFFER_FOR_MAXREAD_MS : 0;
		//long minRemainingReadDurationToExecuteTask = this.cycleTasksManager.getMinDurationToExecuteReadTask();
		//System.out.println("LastReadDuration: "+lastReadDuration+", LastReadTasks: "+lastReadTasks+", BufferForMaxRead: "+WaitDelayHandler.BUFFER_FOR_MAXREAD_MS+" -> MaxReadDuration: "+maxReadDuration+ ", required duration to execute a task: "+minRemainingReadDurationToExecuteTask);
		
		/*
		 * TODO: Measure time lastInReadStateDuration instead of lastReadDuration ?!
		 * 
		 */
		
		//if(this.inReadState && this.inReadStateSince != null) { // will not be executed for first read task in cycle
			//var remainingReadDuration = lastReadDuration - Duration.between(this.inReadStateSince, Instant.now()).toMillis();
			//System.out.println("inReadState, remainingDuration: "+remainingReadDuration);
		
			// if remainingReadDuration < minRemainingReadDurationToExecuteTask ? --> skip execute next task 
		//}
		
		var task = this.cycleTasksManager.getNextTask();

		if (this.cycleTasksManager.consumeBackpressurePauseCycle()) {
		    this.readCycleBackpressureSkipCount.incrementAndGet();
		}

		if (ENABLE_READ_BATCHING && task instanceof ReadTask firstReadTask && this.isBatchable(firstReadTask)) {
			var batchTasks = this.cycleTasksManager.collectBatchableReadTasks(firstReadTask);
			int batchTaskCount = batchTasks.size();

			int batchTimeoutMs = this.cycleTasksManager.getAttemptTimeoutMsForReadBatch(batchTaskCount);

			for (var batchTask : batchTasks) {
				batchTask.setAttemptTimeoutMs(batchTimeoutMs);
			}

			//this.log.info("TEMP executing read batch: taskCount=" + batchTasks.size());
			var batchExecution = this.readBatchExecutor.execute(this.bridge, batchTasks, batchTimeoutMs);

			boolean hasTaskError = batchExecution.taskResults().stream()
			        .anyMatch(r -> r.state() instanceof ExecuteState.Error);

			int expected = batchExecution.expectedResponses();
			int received = batchExecution.receivedResponses();
			boolean complete = expected > 0 && !batchExecution.hasMissingResponses();

			ExecuteState batchCycleState;
			var batchCycleStateText = "";

			if (hasTaskError) {
			    this.consecutiveSoftSkipCycles = 0;
			    batchCycleState = new ExecuteState.Error(new OpenemsException(
			            "Hard-fail read batch: at least one read task failed"));
			    batchCycleStateText = "Failed";

			} else if (complete) {
			    this.consecutiveSoftSkipCycles = 0;
			    batchCycleState = ExecuteState.OK;
			    batchCycleStateText = "OK";

			} else {
			    this.consecutiveSoftSkipCycles++;
			    batchCycleState = new ExecuteState.Skipped(new RctReadSkippedException(
			            "Soft-skip read cycle: expectedResponses=" + expected
			                    + " receivedResponses=" + received,
			            new java.net.SocketTimeoutException("Partial or missing batch response")));
			    batchCycleStateText = "Skipped";
			}

			if (batchCycleState == ExecuteState.OK) {
				this.cycleTasksManager.onSuccessfulCommunicationCycle();
			} else {
				/*
				this.log.info("TEMP read batch cycle result: "
						+ batchCycleStateText
						+ " expected=" + expected
						+ " resolved=" + received
						+ " device=" + batchExecution.deviceResponses()
						+ " cache=" + batchExecution.cachedResponses()
						+ " missing=" + (expected - received)
						+ " consecutiveSoftSkipCycles=" + this.consecutiveSoftSkipCycles
						+ " maxCacheCycleAge=" + batchExecution.maxCacheCycleAge());
				*/
			}

			boolean noDeviceResponses = batchExecution.deviceResponses() == 0;

			if (batchCycleState instanceof ExecuteState.Skipped
			        && batchExecution.hasMissingResponses()
			        && noDeviceResponses
			        && this.consecutiveSoftSkipCycles >= 2) {

				this.cycleTasksManager.onBackpressureReadAttempt(
				        batchExecution.cachedResponses(),
				        batchExecution.maxCacheCycleAge());

				this.cycleTasksManager.pauseCommunicationDueToBackpressure();
			}

			for (var batchResult : batchExecution.taskResults()) {
				var state = batchResult.state();

				this.handleTaskResult(batchResult.task(),
						state,
						new AdjustedExecuteResult(state, false),
						batchResult.durationNanos(),
						false);
			}
			
			//int expected = batchExecution.expectedResponses();
			//int received = batchExecution.receivedResponses();
			//int missing = expected - received;

			// Backpressure only for relevant partial batches.
			// A single missing response is tolerated.
			//if (missing >= 2) {
			//	this.cycleTasksManager.skipNextReadCycleDueToBatchPartial(expected, received);
			//}

			//for (var batchResult : batchExecution.taskResults()) {
			//	var adjusted = this.adjustReadTaskResultForSoftFailBurst(batchResult.task(), batchResult.state());
			//	this.handleTaskResult(batchResult.task(), adjusted.state(), adjusted, batchResult.durationNanos());
			//}

			this.countReadCycleResult(batchCycleState);
			this.logReadStats();
			return;
		}

		//if(task instanceof ReadObjectTask) {
		//	if(!this.inReadState) {
		//		this.inReadState = true;
		//		this.inReadStateSince = Instant.now();
		//	}
		//} else {
		//	this.inReadState = false;
		//}
		
		if (task instanceof ReadTask readTask) {
		    int timeoutMs = this.cycleTasksManager.getAttemptTimeoutMsForNextReadTask();
		    //readTask.setAttemptTimeoutMs(Math.min(timeoutMs, 80));
		    readTask.setAttemptTimeoutMs(timeoutMs);
		}
		
		// execute the task and measure execution time
		long startNanos = System.nanoTime();
		var originalResult = this.execute.apply(task);
		long durationNanos = System.nanoTime() - startNanos;

		var adjustedResult = this.adjustReadTaskResultForSoftFailBurst(task, originalResult);
		var result = adjustedResult.state();

		this.handleTaskResult(task, result, adjustedResult, durationNanos, false);

		if (task instanceof ReadTask && result == ExecuteState.OK) {
		    this.cycleTasksManager.onSuccessfulCommunicationCycle();
		}

		this.logReadStats();
	}

	private void handleTaskResult(Task task, ExecuteState result,
			AdjustedExecuteResult adjustedResult, long durationNanos, boolean countStats) {
		this.cycleTasksManager.onTaskExecuted(task, result, durationNanos);
		//this.log.info("TEMP handle task result: task=" + task + " result=" + result);

		/*
		if (task instanceof ReadTask readTask) {
			this.log.info("ReadTask result: state=" + result
					+ " attemptTimeoutMs=" + readTask.getAttemptTimeoutMs()
					+ " durationMs=" + TimeUnit.NANOSECONDS.toMillis(durationNanos));
			//readTask.clearAttemptTimeoutMs();
		} else if (result instanceof ExecuteState.Error) {
		    this.log.warn("Task result: task=" + task + " result=" + result);
		}
		*/

		switch (result) {
		case ExecuteState.Ok es -> {
			if (countStats) {
				//this.successCount.incrementAndGet();
			}
			//this.successCount.incrementAndGet();
			// no exception & at least one sub-task executed
			this.markComponentAsDefective(task.getParent(), false);
		}
		case ExecuteState.NoOp es -> {
			// did not execute anything
			doNothing();
		}
		case ExecuteState.Skipped es -> {
			if (countStats) {
				if (adjustedResult.suppressedHardFailEscalation()) {
					//this.softFailBurstSkippedCount.incrementAndGet();
				} else {
					//this.softFailSkippedCount.incrementAndGet();
				}
			}
			//if (adjustedResult.suppressedHardFailEscalation()) {
			//	this.softFailBurstSkippedCount.incrementAndGet();
			//} else {
			//	this.softFailSkippedCount.incrementAndGet();
			//}
			/*
			 * Soft-fail:
			 * - do NOT mark defective
			 * - do NOT invalidate
			 * - keep previous channel values
			 * - retry LOW priority ReadTasks once in the next cycle
			 */
			if (task instanceof ReadTask readTask && readTask.getPriority() == Priority.LOW) {
				this.tasksSupplier.retryLowPriorityReadTaskNextCycle(readTask);
			}
			doNothing();
		}
		case ExecuteState.Error es -> {
			if (countStats) {
				//this.hardFailErrorCount.incrementAndGet();
			}
			//this.hardFailErrorCount.incrementAndGet();
			this.markComponentAsDefective(task.getParent(), true);
			// invalidate elements of this task
			this.invalidate.accept(task.getElements());
		}
		}
	}

	/**
	 * Result of post-processing a task execution state in the worker.
	 *
	 * <p>
	 * This is used to distinguish normal soft-fails from hard-fail escalations
	 * that were suppressed because the current or previous read phase showed
	 * a soft-fail burst.
	 */
	private record AdjustedExecuteResult(ExecuteState state, boolean suppressedHardFailEscalation) {
	}

	/**
	 * Adjusts the execution result of a read task for soft-fail burst conditions.
	 *
	 * <p>
	 * If a hard-fail was caused only by escalation after repeated skipped cycles,
	 * and the current or previous read phase indicates a soft-fail burst, the
	 * hard-fail is downgraded to {@link ExecuteState.Skipped}.
	 *
	 * @param task the executed task
	 * @param result the original execution result
	 * @return the adjusted execution result
	 */
	private AdjustedExecuteResult adjustReadTaskResultForSoftFailBurst(Task task, ExecuteState result) {
		if (!(task instanceof ReadTask)) {
			return new AdjustedExecuteResult(result, false);
		}
		if (!(result instanceof ExecuteState.Error error)) {
			return new AdjustedExecuteResult(result, false);
		}

		var ex = error.exception();

		if (ex instanceof RctReadSkippedException skippedEx && skippedEx.isEscalated()
				&& task instanceof ReadTask readTask) {

			boolean inSoftFailBurstContext =
					this.cycleTasksManager.previousReadPhaseHadSoftFailBurst()
					|| this.cycleTasksManager.readPhaseHasSoftFailBurst();

			if (!inSoftFailBurstContext) {
				return new AdjustedExecuteResult(result, false);
			}

			int suppressions = readTask.incrementSuppressedHardFailEscalations();
			if (suppressions <= MAX_BURST_ESCALATION_SUPPRESSIONS) {
				this.log.info("TEMP suppress hard-fail escalation due to soft-fail burst context: "
						+ "task=" + task
						+ " suppressions=" + suppressions
						+ " maxSuppressions=" + MAX_BURST_ESCALATION_SUPPRESSIONS
						+ " message=" + ex.getMessage());

				return new AdjustedExecuteResult(new ExecuteState.Skipped(ex), true);
			}

			return new AdjustedExecuteResult(result, false);
		}

		return new AdjustedExecuteResult(result, false);
	}

	private void countReadCycleResult(ExecuteState result) {
		switch (result) {
		case ExecuteState.Ok es -> this.readCycleSuccessCount.incrementAndGet();
		case ExecuteState.Skipped es -> this.readCycleSoftFailCount.incrementAndGet();
		case ExecuteState.Error es -> this.readCycleHardFailCount.incrementAndGet();
		case ExecuteState.NoOp es -> doNothing();
		}
	}

	private void logReadStats() {
		final long now = System.currentTimeMillis();
		final long elapsed = now - this.skippedStatsWindowStartedMs;

		if (elapsed < SKIPPED_STATS_WINDOW_MS) {
			return;
		}

		final long success = this.readCycleSuccessCount.getAndSet(0);
		final long soft = this.readCycleSoftFailCount.getAndSet(0);
		final long hard = this.readCycleHardFailCount.getAndSet(0);
		final long backpressure = this.readCycleBackpressureSkipCount.getAndSet(0);

		this.skippedStatsWindowStartedMs = now;

		this.log.info(
				"Read batch cycle statistics for last 10 minutes: "
						+ "success [" + success + "], "
						+ "soft-fail [" + soft + "], "
						+ "backpressure-skip [" + backpressure + "], "
						+ "hard-fail [" + hard + "]");
	}

	private boolean isBatchable(ReadTask task) {
		return task instanceof io.openems.edge.rctpower.bridge.api.task.ReadObjectTask;
	}

	/**
	 * Marks the given {@link ModbusComponent} as defective or non-defective.
	 * 
	 * <ul>
	 * <li>Sets 'ModbusCommunicationFailed' Channel of the ModbusComponent
	 * <li>Adds/Removes the component to/from the {@link DefectiveComponents}
	 * </ul>
	 * 
	 * @param component   the {@link ModbusComponent}
	 * @param isDefective mark as defective (true) or non-defective (false)
	 */
	private void markComponentAsDefective(RctComponent component, boolean isDefective) {
		if (component != null) {
			if (isDefective) {
				// Component is defective
				this.defectiveComponents.add(component.id());
				component._setRctCommunicationFailed(true);

			} else {
				// Read from/Write to Component was successful
				this.defectiveComponents.remove(component.id());
				component._setRctCommunicationFailed(false);
			}
		}
	}

	/**
	 * Adds the protocol.
	 *
	 * @param sourceId Component-ID of the source
	 * @param protocol the ModbusProtocol
	 */
	public void addProtocol(String sourceId, RctProtocol protocol) {
		this.tasksSupplier.addProtocol(sourceId, protocol, this.invalidate);
		this.defectiveComponents.remove(sourceId); // Cleanup
	}

	/**
	 * Removes the protocol.
	 *
	 * @param sourceId Component-ID of the source
	 */
	public void removeProtocol(String sourceId) {
		this.tasksSupplier.removeProtocol(sourceId, this.invalidate);
		this.defectiveComponents.remove(sourceId); // Cleanup
	}

	/**
	 * Retry Modbus communication to given Component-ID.
	 * 
	 * <p>
	 * See {@link BridgeModbus#retryModbusCommunication(String)}
	 * 
	 * @param sourceId Component-ID of the source
	 */
	public void retryRctCommunication(String sourceId) {
		this.defectiveComponents.remove(sourceId);
	}

	/**
	 * Called on EXECUTE_WRITE event.
	 */
	public void onExecuteWrite() {
		this.cycleTasksManager.onExecuteWrite();
	}

	/**
	 * Called on BEFORE_PROCESS_IMAGE event.
	 */
	public void onBeforeProcessImage() {
		this.cycleTasksManager.onBeforeProcessImage();
	}
}