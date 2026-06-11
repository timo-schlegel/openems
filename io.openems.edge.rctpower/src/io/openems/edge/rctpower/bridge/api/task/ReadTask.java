package io.openems.edge.rctpower.bridge.api.task;

import io.openems.edge.rctpower.bridge.api.AbstractRctPowerBridge;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.worker.RctReadWorker.ResponseEntry;

/**
 * A Modbus 'ReadTask' is holding references to one or more Modbus
 * {@link ModbusElement}s which have register addresses in the same range. The
 * ReadTask handles the execution (query) on this range. @{link WriteTask}
 * inherits from ReadTask.
 */
public non-sealed interface ReadTask extends Task {

	int incrementSuppressedHardFailEscalations();

	void resetSuppressedHardFailEscalations();

	int getSuppressedHardFailEscalations();

    public void setAttemptTimeoutMs(Integer attemptTimeoutMs);

    public Integer getAttemptTimeoutMs();

    public void clearAttemptTimeoutMs();

    ExecuteState executeBatchResponse(AbstractRctPowerBridge bridge, RctResponse response, ResponseEntry entry, long durationNanos);

    ExecuteState executeBatchSkipped(AbstractRctPowerBridge bridge, Exception e, long durationNanos);
}