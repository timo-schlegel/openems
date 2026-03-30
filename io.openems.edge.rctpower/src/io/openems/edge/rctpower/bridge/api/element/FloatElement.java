package io.openems.edge.rctpower.bridge.api.element;

import java.nio.ByteBuffer;

import io.openems.common.types.OpenemsType;

/**
 * A FloatElement represents a Float value according to IEEE-754 in an
 * {@link AbstractDoubleWordElement}.
 */
public class FloatElement extends AbstractDoubleWordElement<FloatElement, Float> {

	public FloatElement(int address) {
		super(OpenemsType.FLOAT, address);
	}

	@Override
	protected FloatElement self() {
		return this;
	}

	@Override
	protected Float byteBufferToValue(ByteBuffer buff) {
		return buff.getFloat(0);
	}

	@Override
	protected void valueToByteBuffer(ByteBuffer buff, Float value) {
		buff.putFloat(value.floatValue());
	}
}