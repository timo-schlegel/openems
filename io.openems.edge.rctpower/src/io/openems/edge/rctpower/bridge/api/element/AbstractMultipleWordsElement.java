package io.openems.edge.rctpower.bridge.api.element;

import io.openems.common.types.OpenemsType;
import io.openems.edge.rctpower.bridge.api.RctObject;

/**
 * A MultipleWordElement has a size of a definable length of bytes.
 *
 * @param <SELF> the subclass of myself
 * @param <T>    the OpenEMS type
 */
public abstract class AbstractMultipleWordsElement<SELF extends AbstractRctElement<SELF, RctObject[], T>, T>
		extends RctObjectElement<SELF, T> {

	private WordOrder wordOrder = WordOrder.MSWLSW;

	protected AbstractMultipleWordsElement(OpenemsType type, int startAddress, int length) {
		super(type, startAddress, length);
	}

	@Override
	protected final T registersToValue(RctObject[] registers) {
		return this.commonRegistersToValue(registers, this.wordOrder);
	}

	@Override
	protected RctObject[] valueToRaw(T value) {
		return this.valueToRaw(value, this.wordOrder);
	}

	/**
	 * Sets the Word-Order. Default is "MWSLSW" - "Most Significant Word; Least
	 * Significant Word". See http://www.simplymodbus.ca/FAQ.htm#Order.
	 *
	 * @param wordOrder the WordOrder
	 * @return myself
	 */
	public final SELF wordOrder(WordOrder wordOrder) {
		this.wordOrder = wordOrder;
		return this.self();
	}

	protected WordOrder getWordOrder() {
		return this.wordOrder;
	}

}