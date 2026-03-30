package io.openems.edge.rctpower.bridge.api.task;

import java.util.Arrays;
import java.util.function.Consumer;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.rctpower.bridge.api.RctObject;
import io.openems.edge.rctpower.bridge.api.RctRequest;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.element.RctElement;
import io.openems.edge.rctpower.bridge.api.element.RctObjectElement;
import io.openems.edge.common.taskmanager.Priority;

@SuppressWarnings("rawtypes")
public abstract class AbstractReadObjectTask<//
		REQUEST extends RctRequest, //
		RESPONSE extends RctResponse> //
		extends AbstractReadTask<REQUEST, RESPONSE, RctObjectElement, RctObject> {

	public AbstractReadObjectTask(String name, Consumer<ExecuteState> onExecute, Class<RESPONSE> responseClazz,
			int oid, Priority priority, RctElement... elements) {
		super(name, onExecute, responseClazz, RctObjectElement.class, oid, priority, elements);
	}

	@SuppressWarnings("unchecked")
	@Override
	protected final void handleResponse(RctObjectElement element, int position, RctObject[] response)
			throws OpenemsException {
		element.setInputValue(Arrays.copyOfRange(response, position, position + element.length));
	}

	@Override
	protected final int calculateNextPosition(RctElement modbusElement, int position) {
		return position + modbusElement.length;
	}
}