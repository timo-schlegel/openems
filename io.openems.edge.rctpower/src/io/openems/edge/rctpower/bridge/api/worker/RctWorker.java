package io.openems.edge.rctpower.bridge.api.worker;

import static io.openems.common.utils.FunctionUtils.doNothing;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.openems.common.worker.AbstractImmediateWorker;
import io.openems.edge.rctpower.bridge.api.RctComponent;
import io.openems.edge.rctpower.bridge.api.RctProtocol;
import io.openems.edge.rctpower.bridge.api.Config.LogHandler;
import io.openems.edge.rctpower.bridge.api.element.RctElement;
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

	// Callbacks
	private final Function<Task, ExecuteState> execute;
	private final Consumer<RctElement[]> invalidate;

	private final DefectiveComponents defectiveComponents;
	private final TasksSupplierImpl tasksSupplier;
	private final CycleTasksManager cycleTasksManager;

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
	public RctWorker(Function<Task, ExecuteState> execute, Consumer<RctElement[]> invalidate,
			Consumer<Boolean> cycleTimeIsTooShortChannel, Consumer<Long> cycleDelayChannel,
			Supplier<LogHandler> logHandler
			) {
		this.execute = execute;
		this.invalidate = invalidate;

		this.defectiveComponents = new DefectiveComponents(logHandler);
		this.tasksSupplier = new TasksSupplierImpl(logHandler);
		this.cycleTasksManager = new CycleTasksManager(this.tasksSupplier, this.defectiveComponents,
				cycleTimeIsTooShortChannel, cycleDelayChannel, logHandler);
	}

	@Override
	protected void forever() throws InterruptedException {
		var task = this.cycleTasksManager.getNextTask();

		// execute the task
		var result = this.execute.apply(task);

		switch (result) {
		case ExecuteState.Ok es ->
			// no exception & at least one sub-task executed
			this.markComponentAsDefective(task.getParent(), false);
		case ExecuteState.NoOp es ->
			// did not execute anything
			doNothing();
		case ExecuteState.Error es -> {
			this.markComponentAsDefective(task.getParent(), true);
			// invalidate elements of this task
			this.invalidate.accept(task.getElements());
		}
		}
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