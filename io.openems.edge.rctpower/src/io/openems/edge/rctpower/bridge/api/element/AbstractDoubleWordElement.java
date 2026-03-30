package io.openems.edge.rctpower.bridge.api.element;

import io.openems.common.types.OpenemsType;
import io.openems.edge.rctpower.bridge.api.RctObject;

/**
 * A DoubleWordElement has a size of 4 bytes or 32 bit.
 *
 * @param <SELF> the subclass of myself
 * @param <T>    the OpenEMS type
 */
public abstract class AbstractDoubleWordElement<SELF extends AbstractRctElement<SELF, RctObject[], T>, T>
		extends AbstractMultipleWordsElement<SELF, T> {

	public AbstractDoubleWordElement(OpenemsType type, int startAddress) {
		super(type, startAddress, 4);
	}

}