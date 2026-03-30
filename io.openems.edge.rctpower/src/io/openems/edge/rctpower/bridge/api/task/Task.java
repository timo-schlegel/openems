package io.openems.edge.rctpower.bridge.api.task;

import io.openems.edge.common.taskmanager.ManagedTask;
import io.openems.edge.rctpower.bridge.api.AbstractOpenemsRctComponent;
import io.openems.edge.rctpower.bridge.api.AbstractRctPowerBridge;
import io.openems.edge.rctpower.bridge.api.RctComponent;
import io.openems.edge.rctpower.bridge.api.element.RctElement;

public sealed interface Task extends ManagedTask permits AbstractTask, ReadTask, WriteTask, WaitTask {

	/**
	 * Gets the ModbusElements.
	 *
	 * @return an array of ModbusElements
	 */
	public RctElement[] getElements();

	/**
	 * Gets the start Modbus register address.
	 *
	 * @return the address
	 */
	public int getStartAddress();

	/**
	 * Gets the length from first to last Modbus register address.
	 *
	 * @return the address
	 */
	public int getLength();

	/**
	 * Sets the parent.
	 *
	 * @param parent the parent {@link AbstractOpenemsModbusComponent}.
	 */
	public void setParent(AbstractOpenemsRctComponent parent);

	/**
	 * Gets the parent.
	 *
	 * @return the parent
	 */
	public RctComponent getParent();

	/**
	 * This is called on deactivate of the Modbus-Bridge. It can be used to clear
	 * any references like listeners.
	 */
	public void deactivate();

	/**
	 * Executes the tasks - i.e. sends the query of a ReadTask or writes a
	 * WriteTask.
	 *
	 * @param bridge the Modbus-Bridge
	 * @return {@link ExecuteState}
	 */
	public ExecuteState execute(AbstractRctPowerBridge bridge);

	public static sealed interface ExecuteState {

		public static final class Ok implements ExecuteState {
			private Ok() {
			}
		}

		/** Successfully executed request(s). */
		public static final ExecuteState.Ok OK = new ExecuteState.Ok();

		public static final class NoOp implements ExecuteState {
			private NoOp() {
			}
		}

		/** No available requests -> no operation. */
		public static final ExecuteState.NoOp NO_OP = new ExecuteState.NoOp();

		/** Executing request(s) failed. */
		public static final record Error(Exception exception) implements ExecuteState {
		}

	}
}