package io.openems.edge.rctpower.bridge.api.task;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Consumer;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.utils.FunctionUtils;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.rctpower.bridge.api.RctFrame;
import io.openems.edge.rctpower.bridge.api.RctObject;
import io.openems.edge.rctpower.bridge.api.RctRequest;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.element.RctElement;
import io.openems.edge.rctpower.bridge.api.element.RctObjectElement;

/**
 * Implements a Read Holding Register Task, implementing Modbus function code 3
 * (http://www.simplymodbus.ca/FC03.htm).
 */
public class ReadObjectTask
		extends AbstractReadObjectTask<RctRequest, RctResponse> {

	public ReadObjectTask(int oid, Priority priority, RctElement... elements) {
		this(FunctionUtils::doNothing, oid, priority, elements);
	}

	public ReadObjectTask(Consumer<ExecuteState> onExecute, int oid, Priority priority,
			RctElement... elements) {
		super("ReadObject", onExecute, RctResponse.class, oid, priority, elements);
	}

	@Override
	protected RctRequest createRctRequest() {
		//System.out.println("createRctRequest for OID: "+Integer.toHexString(oid));
		return new RctRequest((byte)0x01, ByteBuffer.allocate(4).putInt(this.oid).array());
	}

	@Override
	protected RctObject[] parseResponse(RctResponse response) throws OpenemsException {

		RctObject rctObject[] = new RctObject[1];
		rctObject[0] = new RctObject();
		rctObject[0].setValue(response.getData());		
		
		return rctObject;

		//return null;
		//return response.getRegisters();
	}

	@Override
	protected String payloadToString(RctResponse response) {
		HexFormat hexFormat = HexFormat.of();
    	return "0x"+hexFormat.formatHex(response.getData());
	}	
}