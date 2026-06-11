package io.openems.edge.rctpower.bridge.api.task;

import java.util.function.Consumer;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.utils.FunctionUtils;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.rctpower.bridge.api.RctObject;
import io.openems.edge.rctpower.bridge.api.RctRequest;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.element.RctElement;

/**
 * Executes a read operation for a single {@link ReadObject}.
 *
 * <p>
 * This task sends one RCT read request for the given Object ID (OID) and
 * processes the corresponding {@link RctResponse}. The response is parsed
 * and mapped to the configured {@link RctElement}s.
 *
 * <p>
 * This task represents the single-object variant. For optimized execution
 * of multiple read operations, see {@link BatchReadObjectTask}.
 */
public class ReadObjectTask
		extends AbstractReadObjectTask<RctRequest, RctResponse> {

	private final ReadObject readObject;

	public ReadObjectTask(int oid, Priority priority, RctElement... elements) {
		this(new ReadObject(oid, elements), priority);
	}

	public ReadObjectTask(ReadObject readObject, Priority priority) {
		this(FunctionUtils::doNothing, readObject, priority);
	}

	public ReadObjectTask(Consumer<ExecuteState> onExecute, int oid, Priority priority, RctElement... elements) {
		this(onExecute, new ReadObject(oid, elements), priority);
	}

	public ReadObjectTask(Consumer<ExecuteState> onExecute, ReadObject readObject, Priority priority) {
		super("ReadObject", onExecute, RctResponse.class, readObject.getOid(), priority, readObject.getElements());
		this.readObject = readObject;
	}

	public ReadObject getReadObject() {
		return this.readObject;
	}

	@Override
	protected RctRequest createRctRequest() {
		return this.readObject.createRequest();
	}

	@Override
	protected RctObject[] parseResponse(RctResponse response) throws OpenemsException {
		return this.readObject.parseResponse(response);
	}

	@Override
	protected String payloadToString(RctResponse response) {
		return this.readObject.payloadToString(response);
	}
}