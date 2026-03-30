package io.openems.edge.rctpower.bridge.api.task;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.function.Consumer;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.utils.FunctionUtils;
import io.openems.edge.rctpower.bridge.api.RctObject;
import io.openems.edge.rctpower.bridge.api.RctRequest;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.element.RctObjectElement;

public class WriteObjectTask extends
		AbstractWriteTask.Single<RctRequest, RctResponse, RctObjectElement<?, ?>> {

	public WriteObjectTask(int oid, RctObjectElement<?, ?> element) {
		this(FunctionUtils::doNothing, oid, element);
	}

	public WriteObjectTask(Consumer<ExecuteState> onExecute, int oid,
			RctObjectElement<?, ?> element) {
		super("WriteObject", onExecute, RctResponse.class, oid, element);
	}

	@Override
	protected RctRequest createRctRequest() throws OpenemsException {					
		RctObject[] objects = this.element.getNextWriteValueAndReset();
		if (objects == null) {
			return null;
		}
		
		// found value -> write
		RctObject object = objects[0];
		return new RctRequest((byte)0x02, ByteBuffer.allocate(4).putInt(this.oid).array(),object.toBytes());
	}

	@Override
	protected String payloadToString(RctRequest request) {
		HexFormat hexFormat = HexFormat.of();
	    return "0x"+hexFormat.formatHex(request.getData());
	}
}