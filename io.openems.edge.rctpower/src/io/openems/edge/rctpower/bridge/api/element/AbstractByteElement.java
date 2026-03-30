package io.openems.edge.rctpower.bridge.api.element;

import io.openems.common.types.OpenemsType;
import io.openems.edge.rctpower.bridge.api.RctObject;

/**
 * A WordElement has a size of one byte or 8 bit.
 *
 * @param <SELF> the subclass of myself
 * @param <T>    the OpenEMS type
 */
public abstract class AbstractByteElement<SELF extends RctObjectElement<SELF, T>, T>
		extends RctObjectElement<SELF, T> {

	public AbstractByteElement(OpenemsType type, int startAddress) {
		super(type, startAddress, 1);
	}

	@Override
	protected T registersToValue(RctObject[] registers) {
		// length of registers array is guaranteed to be 1 here.
		return this.commonRegistersToValue(registers, WordOrder.MSWLSW /* makes no difference for one byte */);
	}

	@Override
	protected RctObject[] valueToRaw(T value) {
		return this.valueToRaw(value, WordOrder.MSWLSW /* makes no difference for one byte */);
	}

}