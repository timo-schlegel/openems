package io.openems.edge.rctpower.bridge.api.worker.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.taskmanager.TasksManager;
import io.openems.edge.common.type.Tuple;
import io.openems.edge.rctpower.bridge.api.RctProtocol;
import io.openems.edge.rctpower.bridge.api.Config.LogHandler;
import io.openems.edge.rctpower.bridge.api.element.RctElement;
import io.openems.edge.rctpower.bridge.api.task.ReadTask;
import io.openems.edge.rctpower.bridge.api.task.Task;
import io.openems.edge.rctpower.bridge.api.task.WriteTask;

/**
 * Supplies Tasks.
 */
public class TasksSupplierImpl implements TasksSupplier {

	private final Logger log = LoggerFactory.getLogger(TasksSupplierImpl.class);
	private final Supplier<LogHandler> logHandler;

	public TasksSupplierImpl(Supplier<LogHandler> logHandler) {
		this.logHandler = logHandler;
	}

	/**
	 * Source-ID -> TasksManager for {@link Task}s.
	 */
	private final Map<String, TasksManager<Task>> taskManagers = new HashMap<>();

	/**
	 * Queue of LOW priority {@link ReadTask}s.
	 */
	private final Queue<Tuple<String, ReadTask>> nextLowPriorityTasks = new LinkedList<>();

	private static final int LOW_PRIORITY_RETRY_CYCLES = 2;

	/**
	 * A single LOW priority {@link ReadTask} scheduled for preferred retry
	 * in the next {@value #LOW_PRIORITY_RETRY_CYCLES} cycles after a soft-fail
	 * (skipped execution).
	 */
	private ReadTask retryLowPriorityTask = null;
	private int retryLowPriorityTaskRemainingCycles = 0;
	
	/**
	 * Adds (or replaces) the protocol identified by its sourceId.
	 * 
	 * <p>
	 * If a protocol with the same sourceId existed before,
	 * {@link #removeProtocol(String, Consumer)} is called internally first.
	 * 
	 * @param sourceId   Component-ID of the source
	 * @param protocol   the ModbusProtocol
	 * @param invalidate invalidates the given {@link ModbusElement}s after read
	 *                   errors
	 */
	public synchronized void addProtocol(String sourceId, RctProtocol protocol,
			Consumer<RctElement[]> invalidate) {
		this.removeProtocol(sourceId, invalidate); // remove if sourceId exists

		this.traceLog(() -> "Add Protocol for " //
				+ "[" + sourceId + "] with " //
				+ "[" + protocol.getTaskManager().countTasks() + "] tasks");
		this.taskManagers.put(sourceId, protocol.getTaskManager());
	}

	/**
	 * Removes the protocol and invalidates all {@link ModbusElement}s.
	 *
	 * @param sourceId   Component-ID of the source
	 * @param invalidate invalidates the given {@link ModbusElement}s after read
	 *                   errors
	 */
	public synchronized void removeProtocol(String sourceId, Consumer<RctElement[]> invalidate) {
		var taskManager = this.taskManagers.remove(sourceId);
		if (taskManager == null) {
			return;
		}

		this.traceLog(() -> "Remove Protocol for " //
				+ "[" + sourceId + "] with " //
				+ "[" + taskManager.countTasks() + "] tasks");
		taskManager.getTasks() //
				.forEach(t -> invalidate.accept(t.getElements()));
		this.nextLowPriorityTasks.removeIf(t -> t.a() == sourceId);

		if (this.retryLowPriorityTask != null) {
			boolean belongsToRemovedProtocol = taskManager.getTasks(Priority.LOW).stream() //
					.filter(ReadTask.class::isInstance).map(ReadTask.class::cast) //
					.anyMatch(task -> task == this.retryLowPriorityTask);

			if (belongsToRemovedProtocol) {
				this.retryLowPriorityTask = null;
				this.retryLowPriorityTaskRemainingCycles = 0;
			}
		}
	}

	@Override
	public synchronized CycleTasks getCycleTasks(DefectiveComponents defectiveComponents) {
		Map<String, LinkedList<Task>> tasks = new HashMap<>();
		// One Low Priority ReadTask; retry skipped LOW task from previous cycle first
		{
			Tuple<String, ReadTask> t = null;

			if (this.retryLowPriorityTask != null && this.retryLowPriorityTaskRemainingCycles > 0) {
			    var retryTask = this.retryLowPriorityTask;

			    for (var entry : this.taskManagers.entrySet()) {
			        var id = entry.getKey();
			        var taskManager = entry.getValue();

			        boolean exists = taskManager.getTasks(Priority.LOW).stream()
			                .filter(ReadTask.class::isInstance)
			                .map(ReadTask.class::cast)
			                .anyMatch(task -> task == retryTask);

			        if (exists) {
			            t = new Tuple<>(id, retryTask);
			            break;
			        }
			    }

			    this.retryLowPriorityTaskRemainingCycles--;

			    if (this.retryLowPriorityTaskRemainingCycles <= 0 || t == null) {
			        this.retryLowPriorityTask = null;
			        this.retryLowPriorityTaskRemainingCycles = 0;
			    }
			}

			if (t == null) {
				t = this.getOneLowPriorityReadTask();
			}

			if (t != null) {
				tasks.computeIfAbsent(t.a(), (ignore) -> new LinkedList<>()) //
						.add(t.b());
			}
		}
		// All High Priority ReadTasks + all WriteTasks
		this.taskManagers.forEach((id, taskManager) -> {
			var list = tasks.computeIfAbsent(id, (ignore) -> new LinkedList<>());
			taskManager.getTasks().stream() //
					.filter(t -> t instanceof WriteTask || t.getPriority() == Priority.HIGH) //
					.forEach(list::add);
		});
		// Filter out defective components
		tasks.forEach((id, componentTasks) -> {
			var isDue = defectiveComponents.isDueForNextTry(id);
			if (isDue == null) {
				// Component is not defective -> keep all tasks
			} else if (isDue) {
				// Component is due for next try -> keep only one random Task
				Collections.shuffle(componentTasks);
				while (componentTasks.size() > 1) {
					componentTasks.pop();
				}
			} else {
				// Component is defective and not due -> drop all tasks
				componentTasks.clear();
			}
		});
		var result = new CycleTasks(//
				tasks.values().stream().flatMap(LinkedList::stream) //
						.filter(ReadTask.class::isInstance).map(ReadTask.class::cast) //
						// Sort HIGH priority to the end
						.sorted((a, b) -> b.getPriority().compareTo(a.getPriority())) //
						.collect(Collectors.toCollection(LinkedList::new)),
				tasks.values().stream().flatMap(LinkedList::stream) //
						.filter(WriteTask.class::isInstance).map(WriteTask.class::cast) //
						.collect(Collectors.toCollection(LinkedList::new)));

		this.traceLog(() -> "Getting " //
				+ "[" + result.reads().size() + "] read and " //
				+ "[" + result.writes().size() + "] write tasks for this Cycle");
		return result;
	}

	/**
	 * Get one LOW priority task.
	 *
	 * @return the next task; null if there is no available task
	 */
	private synchronized Tuple<String, ReadTask> getOneLowPriorityReadTask() {
		var refilledBefore = false;
		while (true) {
			var task = this.nextLowPriorityTasks.poll();
			if (task != null) {
				return task;
			}
			if (refilledBefore) {
				// queue had been refilled before, but still cannot find a matching task -> quit
				return null;
			}
			// refill the queue
			this.taskManagers.forEach((id, taskManager) -> {
				taskManager.getTasks(Priority.LOW).stream() //
						.filter(ReadTask.class::isInstance).map(ReadTask.class::cast) //
						.map(t -> new Tuple<String, ReadTask>(id, t)) //
						.forEach(this.nextLowPriorityTasks::add);
			});
			refilledBefore = true;
		}
	}

	/**
	 * Schedules the given LOW priority {@link ReadTask} for preferred retry
	 * in the next {@value #LOW_PRIORITY_RETRY_CYCLES} cycles.
	 *
	 * <p>
	 * If the task is no longer available when the next cycle is created, it is
	 * ignored and normal LOW-priority rotation continues.
	 *
	 * @param task the LOW priority {@link ReadTask} to retry
	 */
	public synchronized void retryLowPriorityReadTaskNextCycle(ReadTask task) {
	    if (task == null) {
	        return;
	    }

	    if (this.retryLowPriorityTask == task) {
	        this.retryLowPriorityTaskRemainingCycles = Math.max(
	                this.retryLowPriorityTaskRemainingCycles,
	                LOW_PRIORITY_RETRY_CYCLES);
	    } else {
	        this.retryLowPriorityTask = task;
	        this.retryLowPriorityTaskRemainingCycles = LOW_PRIORITY_RETRY_CYCLES;
	    }
	}

	@Override
	public synchronized int getTotalNumberOfTasks() {
		return this.taskManagers.values().stream() //
				.mapToInt(m -> m.countTasks()) //
				.sum();
	}

	private void traceLog(Supplier<String> message) {
		this.logHandler.get().trace(this.log, message);
	}
}