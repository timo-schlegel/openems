package io.openems.edge.rctpower.bridge.api.task;

import java.nio.ByteBuffer;
import java.util.HexFormat;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.rctpower.bridge.api.RctObject;
import io.openems.edge.rctpower.bridge.api.RctRequest;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.element.RctElement;

/**
 * Describes a single RCT read operation for a specific Object ID (OID).
 *
 * <p>
 * A {@link ReadObject} defines:
 * <ul>
 *   <li>the OID to read from the device</li>
 *   <li>the {@link RctElement}s that map the response data</li>
 * </ul>
 *
 * <p>
 * It does not perform any communication itself. Execution is handled by
 * {@link ReadObjectTask} (single read) or {@link BatchReadObjectTask}
 * (batched reads).
 */
public class ReadObject {

	private final int oid;
	private final RctElement[] elements;

	public ReadObject(int oid, RctElement... elements) {
		this.oid = oid;
		this.elements = elements;
	}

	public int getOid() {
		return this.oid;
	}

	public RctElement[] getElements() {
		return this.elements;
	}

	public RctRequest createRequest() {
		return new RctRequest((byte) 0x01, ByteBuffer.allocate(4).putInt(this.oid).array());
	}

	public RctObject[] parseResponse(RctResponse response) throws OpenemsException {
		var result = new RctObject[1];
		result[0] = new RctObject();
		result[0].setValue(response.getData());
		return result;
	}

	public String payloadToString(RctResponse response) {
		return "0x" + HexFormat.of().formatHex(response.getData());
	}
}