package io.openems.edge.rctpower.bridge.api;

import io.openems.edge.common.taskmanager.TasksManager;
import io.openems.edge.rctpower.bridge.api.task.Task;

public class RctProtocol {

	/**
	 * The Parent component.
	 */
	private final AbstractOpenemsRctComponent parent;

	/**
	 * TaskManager for ReadTasks.
	 */
	private final TasksManager<Task> taskManager = new TasksManager<>();

	/**
	 * Creates a new {@link ModbusProtocol}.
	 *
	 * @param parent the {@link AbstractOpenemsModbusComponent} parent
	 * @param tasks  the {@link Task}s
	 */
	public RctProtocol(AbstractOpenemsRctComponent parent, Task... tasks) {
		this.parent = parent;
		this.addTasks(tasks);
	}

	/**
	 * Adds Tasks to the Protocol.
	 *
	 * @param tasks the tasks
	 */
	public synchronized void addTasks(Task... tasks) {
		for (Task task : tasks) {
			this.addTask(task);
		}
	}

	/**
	 * Adds a Task to the Protocol.
	 *
	 * @param task the task
	 */
	public synchronized void addTask(Task task) {
		// add the the parent to the Task
		task.setParent(this.parent);
		// fill taskManager
		this.taskManager.addTask(task);
	}

	/**
	 * Removes a Task from the Protocol.
	 *
	 * @param task the task
	 */
	public synchronized void removeTask(Task task) {
		this.taskManager.removeTask(task);
	}

	/**
	 * Gets the Read-Tasks Manager.
	 *
	 * @return a the TaskManager
	 */
	public TasksManager<Task> getTaskManager() {
		return this.taskManager;
	}

	/**
	 * Deactivate the {@link ModbusProtocol}.
	 */
	public void deactivate() {
		this.taskManager.getTasks() //
				.forEach(Task::deactivate);
	}
}