package io.openems.edge.rctpower.bridge.api.worker.internal;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.rctpower.bridge.api.AbstractRctPowerBridge;
import io.openems.edge.rctpower.bridge.api.Config.LogHandler;
import io.openems.edge.rctpower.bridge.api.task.ReadTask;
import io.openems.edge.rctpower.bridge.api.task.Task;
import io.openems.edge.rctpower.bridge.api.task.Task.ExecuteState;
import io.openems.edge.rctpower.bridge.api.task.WaitTask;
import io.openems.edge.rctpower.bridge.api.worker.RctWorker;

/**
 * Manages the Read-, Write- and Wait-Tasks for one Cycle.
 * 
 * <p>
 * <li>{@link #onBeforeProcessImage()} initialize the next Cycle if previous
 * Cycle had finished
 * <li>{@link #onExecuteWrite()} puts Write-Tasks as highest priority
 */
public class CycleTasksManager {

	private final Logger log = LoggerFactory.getLogger(CycleTasksManager.class);

	private final TasksSupplier tasksSupplier;
	private final DefectiveComponents defectiveComponents;
	private final Consumer<Boolean> cycleTimeIsTooShortChannel;
	private final Supplier<LogHandler> logHandler;

	private final WaitDelayHandler waitDelayHandler;
	private final WaitTask.Mutex waitMutexTask = new WaitTask.Mutex();

	private CycleTasks cycleTasks;
	//private Instant readStart;
	//private long lastReadDuration;
	//private long lastReadTasksCount;
	//private long minRemainingReadDurationToExecuteTask = 0;

    private long currentReadPhaseStartNanos = -1L;
    private boolean readPhaseHasSoftFailSkip = false;
    private boolean readPhaseHasSoftFailBurst = false;
    private boolean readPhaseAbortedByBudget = false;
    private boolean previousReadPhaseHadSoftFailBurst = false;
    private int currentReadPhaseReadTasksCount = 0;
    private int softFailCountInReadPhase = 0;
    
    // TEMP
    private static final int COMMUNICATION_PAUSE_CYCLES_BASE = 1;
    private static final int COMMUNICATION_PAUSE_CYCLES_ESCALATED = 2;

    private int skipCommunicationCyclesRemaining = 0;
    private boolean skipCommunicationForCurrentCycle = false;
    private boolean backpressurePauseCycleCounted = false;
    private boolean backpressureSeriesActive = false;
    private int backpressureSeriesPauseCycles = 0;
    private int backpressureSeriesFailedReadCycles = 0;
    private int backpressureSeriesCacheResponses = 0;
    private long backpressureSeriesMaxCacheCycleAge = 0;
    private int consecutiveCommunicationPauses = 0;
    private int consecutiveReadPhaseSoftFailBursts = 0;

    private long lastValidReadPhaseDurationNanos = -1L;
    private long estimatedReadTaskDurationNanos = TimeUnit.MILLISECONDS.toNanos(70);

    private static final long READ_BUDGET_BUFFER_NANOS =
            TimeUnit.MILLISECONDS.toNanos(WaitDelayHandler.BUFFER_MS);

    private static final long READ_TIMEOUT_MIN_MS = 80;
    private static final long READ_TIMEOUT_RESERVE_MS = 50;

	public CycleTasksManager(TasksSupplier tasksSupplier, DefectiveComponents defectiveComponents,
			Consumer<Boolean> cycleTimeIsTooShortChannel, Consumer<Long> cycleDelayChannel,
			Supplier<LogHandler> logHandler) {
		this.tasksSupplier = tasksSupplier;
		this.defectiveComponents = defectiveComponents;
		this.cycleTimeIsTooShortChannel = cycleTimeIsTooShortChannel;
		this.logHandler = logHandler;
		this.waitDelayHandler = new WaitDelayHandler(() -> this.onWaitDelayTaskFinished(), cycleDelayChannel);
	}

	protected static enum StateMachine {
		INITIAL_WAIT, //
		READ_BEFORE_WRITE, //
		WAIT_FOR_WRITE, //
		WRITE, //
		WAIT_BEFORE_READ, //
		READ_AFTER_WRITE, //
		FINISHED
	}

	private StateMachine state = StateMachine.FINISHED;

	/**
	 * Gets the current state.
	 * 
	 * @return the {@link StateMachine}
	 */
	protected StateMachine getState() {
		return this.state;
	}

	/**
	 * Called on BEFORE_PROCESS_IMAGE event.
	 */
	public synchronized void onBeforeProcessImage() {
		// Calculate Delay
		final var waitDelayHandlerLog = this.waitDelayHandler.onBeforeProcessImage(this.logHandler.get().isTrace());

		// Evaluate Cycle-Time-Is-Too-Short, invalidate time measurement and stop early
		var cycleTimeIsTooShort = this.state != StateMachine.FINISHED;
		this.cycleTimeIsTooShortChannel.accept(cycleTimeIsTooShort);
		if (cycleTimeIsTooShort) {
			this.waitDelayHandler.timeIsInvalid();
			this.traceLog(() -> "State: " + this.state + " unchanged" //
					+ " (in onBeforeProcessImage)" //
					+ " Delay [" + this.waitDelayHandler.getWaitDelayTask().initialDelay + "] " //
					+ waitDelayHandlerLog);
			return;
		}

		// Update WaitDelayHandler Queue size
		this.waitDelayHandler.updateTotalNumberOfTasks(this.tasksSupplier.getTotalNumberOfTasks());

		// Apply one-shot read- and write-skip decision for this cycle
		this.skipCommunicationForCurrentCycle = this.skipCommunicationCyclesRemaining > 0;
		this.backpressurePauseCycleCounted = false;

		if (this.skipCommunicationCyclesRemaining > 0) {
		    this.skipCommunicationCyclesRemaining--;
		}

		// Fill queues for this Cycle
		if (this.skipCommunicationForCurrentCycle) {
		    this.backpressureSeriesActive = true;
		    this.backpressureSeriesPauseCycles++;

		    this.cycleTasks = new CycleTasks(new LinkedList<>(), new LinkedList<>());
		    this.waitDelayHandler.skipLearningForCurrentCycle();
		} else {
			this.cycleTasks = this.tasksSupplier.getCycleTasks(this.defectiveComponents);
		}

		// On defectiveComponents invalidate time measurement
		final var containsDefectiveComponents = this.cycleTasks.containsDefectiveComponent(this.defectiveComponents);
		if (containsDefectiveComponents) {
			this.waitDelayHandler.timeIsInvalid();
		}

		// Reset read-phase state for new cycle
		this.resetReadPhaseState();

		// Initialize next Cycle
		this.traceLog(() -> "State: " + this.state + " -> " + StateMachine.INITIAL_WAIT //
				+ " (in onBeforeProcessImage)" //
				+ " Delay [" + this.waitDelayHandler.getWaitDelayTask().initialDelay + "] " //
				+ waitDelayHandlerLog //
				+ (containsDefectiveComponents //
						? " DEFECTIVE_COMPONENT"
						: ""));
		this.state = StateMachine.INITIAL_WAIT;

		// Interrupt wait
		this.waitMutexTask.release();
	}

	/**
	 * Called on EXECUTE_WRITE event.
	 */
	public synchronized void onExecuteWrite() {
		this.traceLog(() -> "State: " + this.state + " -> " + StateMachine.WRITE + " (onExecuteWrite)");

		this.state = StateMachine.WRITE;
		this.waitMutexTask.release();
	}

	/**
	 * Gets the next {@link Task}. This is called in a separate Thread by
	 * {@link RctWorker}.
	 * 
	 * @return next {@link Task}
	 */
	public Task getNextTask() {
		if (this.cycleTasks == null) {
			// Fallback to avoid NPE on race condition
			this.cycleTasks = this.tasksSupplier.getCycleTasks(this.defectiveComponents);
		}

		var previousState = this.state; // drop before release
		var nextTask = switch (this.state) {

		case INITIAL_WAIT -> {
			//this.readStart = null;
			// Waiting for planned waiting time to pass
			//this.waitDelayHandler.getWaitDelayTask();
			// Waiting for EXECUTE_WRITE event
			yield this.waitMutexTask;
		}
			
		case READ_BEFORE_WRITE -> {
			System.out.println("*********** READ BEFORE WRITE");
			// Read-Task available?
			var task = this.cycleTasks.reads().poll();
			if (task != null) {
				yield task;
			}
			// Otherwise -> next state + recursive call
			this.state = StateMachine.WAIT_FOR_WRITE;
			yield this.getNextTask();
		}

		case WAIT_FOR_WRITE ->
			// Waiting for EXECUTE_WRITE event
			this.waitMutexTask;

		case WRITE -> {
			// Write-Task available?
			var task = this.cycleTasks.writes().poll();
			if (task != null) {
				yield task;
			}
			// Otherwise -> next state + recursive call
			this.state = StateMachine.WAIT_BEFORE_READ;
			yield this.getNextTask();
		}

		case WAIT_BEFORE_READ ->
			// Waiting for planned waiting time to pass
			this.waitDelayHandler.getWaitDelayTask();

		case READ_AFTER_WRITE -> {
			//if(this.readStart == null) {
			//	this.readStart = Instant.now();
			//	this.lastReadTasksCount = 0;
			//}
			if (this.currentReadPhaseStartNanos < 0) {
			    this.onReadPhaseStarted();
			}
			// Read-Task available?
			var task = this.cycleTasks.reads().poll();
            if (task == null) {
                this.onReadPhaseFinished();
                this.state = StateMachine.FINISHED;
                yield this.getNextTask();
            }

            if (this.currentReadPhaseReadTasksCount > 0 && !this.shouldExecuteNextReadTask()) {
            	this.traceLog(() -> "Read phase aborted: budget exhausted after soft-fail skip");
                this.onReadPhaseFinished();
                this.state = StateMachine.FINISHED;
                yield this.getNextTask();
            }

            yield task;
			
			/*
			if (task != null) {
				//this.lastReadTasksCount++;
				yield task;
			}
			// Otherwise -> next state + recursive call
			this.state = StateMachine.FINISHED;
			yield this.getNextTask();
			*/
		}

		case FINISHED -> {
			//if(readStart != null) {
			//	this.lastReadDuration = Duration.between(readStart, Instant.now()).toMillis();
			//	var avg = this.lastReadTasksCount > 0 ? this.lastReadDuration/this.lastReadTasksCount : 0;
			//	if(avg>0) {
			//		if (this.lastReadDuration > 0 && this.lastReadTasksCount > 0) {
			//			this.minRemainingReadDurationToExecuteTask = (this.lastReadDuration/this.lastReadTasksCount) + /* Buffer [ms] */ 0; 
			//		}
			//	}
			//	System.out.println("read took "+this.lastReadDuration + "ms for "+this.lastReadTasksCount+" tasks -> average task duration: "+avg+"ms");
			//} else {
			//	this.lastReadDuration = 0;
			//}
			if (this.readPhaseAbortedByBudget) {
		        this.waitDelayHandler.skipLearningForCurrentCycle();
		    }
			
			this.waitDelayHandler.onFinished();
			// Waiting for BEFORE_PROCESS_IMAGE event
			yield this.waitMutexTask;
		}
		};

		if (this.state != previousState) {
			this.traceLog(() -> "State: " + previousState + " -> " + this.state + " (getNextTask)");
		}
		return nextTask;
	}

	public synchronized List<ReadTask> collectBatchableReadTasks(ReadTask firstTask) {
		List<ReadTask> result = new ArrayList<>();
		result.add(firstTask);

		if (this.state != StateMachine.READ_AFTER_WRITE) {
			return result;
		}

		while (true) {
		    var next = this.cycleTasks.reads().peek();
		    if (!(next instanceof ReadTask readTask)) {
		        break;
		    }
		    if (!isBatchableReadTask(readTask)) {
		        break;
		    }

		    this.cycleTasks.reads().poll();
		    result.add(readTask);
		}

		return result;
	}

	private boolean isBatchableReadTask(ReadTask task) {
		return task instanceof io.openems.edge.rctpower.bridge.api.task.ReadObjectTask;
	}

	public synchronized boolean consumeBackpressurePauseCycle() {
	    if (!this.skipCommunicationForCurrentCycle || this.backpressurePauseCycleCounted) {
	        return false;
	    }

	    this.backpressurePauseCycleCounted = true;
	    return true;
	}

	/*
	public synchronized void skipNextCommunicationCycleDueToBatchPartial(
	        int expectedResponses, int receivedResponses) {
	    this.skipCommunicationForNextCycle = true;

	    this.log.info("TEMP skip next RCT communication cycle due to partial read batch: "
	            + "expectedResponses=" + expectedResponses
	            + " receivedResponses=" + receivedResponses);
	}
	*/

	public synchronized void pauseCommunicationDueToBackpressure() {
		if (this.skipCommunicationCyclesRemaining > 0) {
		    return;
		}
	    int pauseCycles = this.consecutiveCommunicationPauses == 0
	            ? COMMUNICATION_PAUSE_CYCLES_BASE
	            : COMMUNICATION_PAUSE_CYCLES_ESCALATED;

	    this.skipCommunicationCyclesRemaining = Math.max(
	            this.skipCommunicationCyclesRemaining,
	            pauseCycles);

	    this.consecutiveCommunicationPauses++;
	}

	/*
	public synchronized long getLastReadDuration() {
		return this.lastReadDuration;
	}

	public synchronized long getLastReadTasksCount() {
		return this.lastReadTasksCount;
	}
	
	public synchronized long getMinDurationToExecuteReadTask() {
		return this.minRemainingReadDurationToExecuteTask;
	}
	*/
	
	private void resetReadPhaseState() {
	    this.currentReadPhaseStartNanos = -1L;
	    this.readPhaseHasSoftFailSkip = false;
	    this.readPhaseHasSoftFailBurst = false;
	    this.readPhaseAbortedByBudget = false;
	    this.currentReadPhaseReadTasksCount = 0;
	    
	    // TEMP
	    this.softFailCountInReadPhase = 0;
	    this.readPhaseHasSoftFailBurst = false;
	}
	
	public void onReadPhaseStarted() {
		this.currentReadPhaseStartNanos = System.nanoTime();
		this.readPhaseHasSoftFailSkip = false;
		this.readPhaseHasSoftFailBurst = false;
		this.readPhaseAbortedByBudget = false;
		this.currentReadPhaseReadTasksCount = 0;
	}

 	public void onReadPhaseFinished() {
	    if (this.currentReadPhaseStartNanos < 0) {
	        return;
	    }

	    long durationNanos = System.nanoTime() - this.currentReadPhaseStartNanos;

	    if (!this.readPhaseAbortedByBudget) {
	        this.lastValidReadPhaseDurationNanos = durationNanos;
	    }

	    // TEMP
	    if (this.readPhaseHasSoftFailBurst) {
	        this.consecutiveReadPhaseSoftFailBursts++;
	    } else {
	        this.consecutiveReadPhaseSoftFailBursts = 0;
	    }
	    // After 2 burst cycles in a row, pause one or more full RCT communication cycles.
	    if (this.consecutiveReadPhaseSoftFailBursts >= 2) {
	        this.pauseCommunicationDueToBackpressure();
	    }	    
	    
	    this.currentReadPhaseStartNanos = -1L;
	    this.previousReadPhaseHadSoftFailBurst = this.readPhaseHasSoftFailBurst;
	}

 	public synchronized void onSuccessfulCommunicationCycle() {
 	    this.consecutiveCommunicationPauses = 0;

 	    if (this.backpressureSeriesActive) {
 	        this.log.info("TEMP RCT backpressure series ended: "
 	                + "pauseCycles=" + this.backpressureSeriesPauseCycles
 	                + " failedReadCycles=" + this.backpressureSeriesFailedReadCycles
 	                + " cachedResponses=" + this.backpressureSeriesCacheResponses
 	                + " maxCacheCycleAge=" + this.backpressureSeriesMaxCacheCycleAge);

 	        this.backpressureSeriesActive = false;
 	        this.backpressureSeriesPauseCycles = 0;
 	        this.backpressureSeriesFailedReadCycles = 0;
 	        this.backpressureSeriesCacheResponses = 0;
 	        this.backpressureSeriesMaxCacheCycleAge = 0;
 	    }
 	}

 	public synchronized void onBackpressureReadAttempt(
 	        int cachedResponses,
 	        long maxCacheCycleAge) {
 	    this.backpressureSeriesActive = true;
 	    this.backpressureSeriesFailedReadCycles++;
 	    this.backpressureSeriesCacheResponses += cachedResponses;
 	    this.backpressureSeriesMaxCacheCycleAge =
 	            Math.max(this.backpressureSeriesMaxCacheCycleAge, maxCacheCycleAge);
 	}

	    public void onTaskExecuted(Task task, ExecuteState state, long durationNanos) {
	        if (!(task instanceof ReadTask)) {
	            return;
	        }
	        
	        this.currentReadPhaseReadTasksCount++;
	        
	        if (state == ExecuteState.OK) {
	            this.estimatedReadTaskDurationNanos = clampReadTaskEstimate(
	            		/* Exponentially Weighted Moving Average (EWMA) -> 70% old value, 30% new value */
	                    (this.estimatedReadTaskDurationNanos * 7 + durationNanos * 3) / 10);
	        } else if (state instanceof ExecuteState.Skipped) {
	            this.readPhaseHasSoftFailSkip = true;
	            this.softFailCountInReadPhase++;

	            if (this.softFailCountInReadPhase >= 3
	                    || this.softFailCountInReadPhase * 2 >= this.currentReadPhaseReadTasksCount) {
	                this.readPhaseHasSoftFailBurst = true;
	            }
	        }
	    }

	    private boolean shouldExecuteNextReadTask() {
	        if (this.readPhaseAbortedByBudget) {
	            return false;
	        }

	        if (!this.canStartAnotherReadTask()) {
	            this.readPhaseAbortedByBudget = true;
	            return false;
	        }

	        return true;
	    }

		private boolean canStartAnotherReadTask() {
			long remainingBudgetMs = this.getRemainingReadPhaseBudgetMs();
			if (remainingBudgetMs < 0) {
				return true;
			}

			long estimatedReadTaskMs = this.getEstimatedReadTaskMs();

			if (!this.readPhaseHasSoftFailSkip) {
				// Before the first soft-fail only use a mild budget guard.
			    // The scheduler should still be able to learn if reads naturally take longer.
				long minRequiredMs = Math.max(READ_TIMEOUT_MIN_MS, estimatedReadTaskMs);
				return remainingBudgetMs >= minRequiredMs;
			}

			// After a soft-fail, stay within the learned read-phase budget
			// and keep additional reserve to avoid cycle overruns.
			return remainingBudgetMs >= READ_TIMEOUT_MIN_MS + READ_TIMEOUT_RESERVE_MS;
		}
	    
	    private long clampReadTaskEstimate(long value) {
	        long min = TimeUnit.MILLISECONDS.toNanos(60);
	        long max = TimeUnit.MILLISECONDS.toNanos(120);
	        return Math.max(min, Math.min(max, value));
	    }


	/**
	 * Gets the estimated execution time of a ReadTask in milliseconds.
	 *
	 * @return the estimated ReadTask duration in milliseconds
	 */
	private long getEstimatedReadTaskMs() {
		return TimeUnit.NANOSECONDS.toMillis(this.estimatedReadTaskDurationNanos);
	}

	/**
	 * Gets the base attempt timeout for the next ReadTask in milliseconds.
	 *
	 * <p>
	 * This is the normal timeout derived from the learned ReadTask duration,
	 * before any budget-based limitation is applied.
	 *
	 * <p>
	 * The first ReadTask in a read phase may need a higher timeout floor because
	 * it can be significantly slower than subsequent ReadTasks.
	 *
	 * @return the base attempt timeout in milliseconds
	 */
	private long getBaseAttemptTimeoutMs() {
		final long maxTimeoutMs = 200;

		long estimatedReadTaskMs = this.getEstimatedReadTaskMs();
		long baseTimeoutMs = (estimatedReadTaskMs * 125L + 99L) / 100L; // ~125 %

		if (this.currentReadPhaseReadTasksCount == 0) {
			// The first read in a read phase may be noticeably slower.
			baseTimeoutMs = Math.max(140, baseTimeoutMs);
		} else {
			// Subsequent reads are usually faster and should not wait as long.
			baseTimeoutMs = Math.max(80, baseTimeoutMs);
		}

		baseTimeoutMs = Math.min(baseTimeoutMs, maxTimeoutMs);
		return baseTimeoutMs;
	}

	/**
	 * Gets the remaining budget of the current read phase in milliseconds.
	 *
	 * <p>
	 * The allowed read-phase budget is the last valid read-phase duration plus
	 * {@link #READ_BUDGET_BUFFER_NANOS}.
	 *
	 * @return the remaining read-phase budget in milliseconds; {@code -1} if no
	 *         valid budget is available
	 */
	private long getRemainingReadPhaseBudgetMs() {
		if (this.currentReadPhaseStartNanos < 0 || this.lastValidReadPhaseDurationNanos <= 0) {
			return -1;
		}

		long elapsedNanos = System.nanoTime() - this.currentReadPhaseStartNanos;
		long allowedBudgetNanos = this.lastValidReadPhaseDurationNanos + READ_BUDGET_BUFFER_NANOS;
		return TimeUnit.NANOSECONDS.toMillis(allowedBudgetNanos - elapsedNanos);
	}

	/**
	 * Gets the budget-limited timeout for the next ReadTask in milliseconds.
	 *
	 * <p>
	 * This limits the timeout to the remaining read-phase budget minus a small
	 * reserve so that a final ReadTask after a soft-fail does not overrun the
	 * current cycle.
	 *
	 * @param fallbackTimeoutMs the timeout to use if no valid budget is available
	 * @return the budget-limited timeout in milliseconds
	 */
	private long getBudgetLimitedAttemptTimeoutMs(long fallbackTimeoutMs) {
		long remainingBudgetMs = this.getRemainingReadPhaseBudgetMs();
		if (remainingBudgetMs < 0) {
			return fallbackTimeoutMs;
		}

		long timeoutMs = remainingBudgetMs - READ_TIMEOUT_RESERVE_MS;
		timeoutMs = Math.max(READ_TIMEOUT_MIN_MS, timeoutMs);
		return Math.min(fallbackTimeoutMs, timeoutMs);
	}

	public int getAttemptTimeoutMsForNextReadTask() {
		long baseTimeoutMs = this.getBaseAttemptTimeoutMs();
		long remainingBudgetMs = this.getRemainingReadPhaseBudgetMs();

		int attemptTimeoutMs;
		if (!this.readPhaseHasSoftFailSkip) {
			attemptTimeoutMs = (int) baseTimeoutMs;
		} else {
			attemptTimeoutMs = (int) this.getBudgetLimitedAttemptTimeoutMs(baseTimeoutMs);
		}

		final int result = attemptTimeoutMs;
		this.traceLog(() -> "Read timeout calculation: "
				+ "softFailSkip=" + this.readPhaseHasSoftFailSkip
				+ " readTasksInPhase=" + this.currentReadPhaseReadTasksCount
				+ " estimatedReadTaskMs=" + this.getEstimatedReadTaskMs()
				+ " baseTimeoutMs=" + baseTimeoutMs
				+ " remainingBudgetMs=" + remainingBudgetMs
				+ " attemptTimeoutMs=" + result);

		return result;
	}

	public int getAttemptTimeoutMsForReadBatch(int batchTaskCount) {
	    long baseTimeoutMs = this.getBaseAttemptTimeoutMs();

	    long calculatedBatchTimeoutMs =
	            baseTimeoutMs + Math.max(0, batchTaskCount - 1) * 25L;

	    final long batchBaseTimeoutMs = Math.min(
	            calculatedBatchTimeoutMs,
	            AbstractRctPowerBridge.DEFAULT_TIMEOUT);

	    long remainingBudgetMs = this.getRemainingReadPhaseBudgetMs();

	    final long timeoutMs = this.getBudgetLimitedAttemptTimeoutMs(batchBaseTimeoutMs);
	    final int result = (int) timeoutMs;

	    this.traceLog(() -> "Read batch timeout calculation: "
	            + "softFailSkip=" + this.readPhaseHasSoftFailSkip
	            + " batchTaskCount=" + batchTaskCount
	            + " readTasksInPhase=" + this.currentReadPhaseReadTasksCount
	            + " estimatedReadTaskMs=" + this.getEstimatedReadTaskMs()
	            + " baseTimeoutMs=" + baseTimeoutMs
	            + " batchBaseTimeoutMs=" + batchBaseTimeoutMs
	            + " remainingBudgetMs=" + remainingBudgetMs
	            + " attemptTimeoutMs=" + result);

	    return result;
	}

	public boolean readPhaseHasSoftFailBurst() {
	    return this.readPhaseHasSoftFailBurst;
	}

	public boolean previousReadPhaseHadSoftFailBurst() {
	    return this.previousReadPhaseHadSoftFailBurst;
	}

	/**
	 * Waiting in INITIAL_WAIT or WAIT_BEFORE_READ finished.
	 */
	private synchronized void onWaitDelayTaskFinished() {
		var previousState = this.state;
		this.state = switch (this.state) {
		// Expected
		case INITIAL_WAIT -> StateMachine.READ_BEFORE_WRITE;
		case WAIT_BEFORE_READ -> StateMachine.READ_AFTER_WRITE;
		// Unexpected (the State has been unexpectedly changed in-between)
		default -> this.state;
		};

		if (this.state != previousState) {
			this.traceLog(() -> "State: " + previousState + " -> " + this.state + " (onWaitDelayTaskFinished)");
		}
	}

	private void traceLog(Supplier<String> message) {
		this.logHandler.get().trace(this.log, message);
	}
}