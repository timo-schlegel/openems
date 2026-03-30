package io.openems.edge.rctpower.bridge.api.element;

import java.nio.ByteBuffer;

import io.openems.common.types.OpenemsType;
import io.openems.edge.common.type.TypeUtils;

/**
 * An UnsignedByteElement represents an Integer value in an
 * {@link AbstractByteElement}.
 */
public class UnsignedByteElement extends AbstractByteElement<UnsignedByteElement, Integer> {

	public UnsignedByteElement(int address) {
		super(OpenemsType.INTEGER, address);
	}

	@Override
	protected UnsignedByteElement self() {
		return this;
	}

	@Override
	protected Integer byteBufferToValue(ByteBuffer buff) {
		return Byte.toUnsignedInt(buff.get(0));
	}

	@Override
	protected void valueToByteBuffer(ByteBuffer buff, Integer value) {
		Integer i = TypeUtils.getAsType(OpenemsType.INTEGER, value);		
		buff.put(i.byteValue());
	}

}