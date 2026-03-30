package io.openems.edge.rctpower.bridge.api.element;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import io.openems.common.types.OpenemsType;
import io.openems.edge.rctpower.bridge.api.RctObject;

/**
 * A ModbusRegisterElement represents one or more Modbus Registers.
 *
 * @param <SELF>   the subclass of myself
 * @param <BINARY> the binary type
 * @param <T>      the OpenEMS type
 */
public abstract class RctObjectElement<SELF extends AbstractRctElement<SELF, RctObject[], T>, T>
		extends AbstractRctElement<SELF, RctObject[], T> {

	/** ByteOrder of the input registers. */
	private ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;

	protected RctObjectElement(OpenemsType type, int startAddress, int length) {
		super(type, startAddress, length);
	}

	protected abstract T byteBufferToValue(ByteBuffer buff);

	protected abstract void valueToByteBuffer(ByteBuffer buff, T value);

	/**
	 * Sets the Byte-Order. Default is "BIG_ENDIAN". See
	 * http://www.simplymodbus.ca/FAQ.htm#Order.
	 *
	 * @param byteOrder the ByteOrder
	 * @return myself
	 */
	public final SELF byteOrder(ByteOrder byteOrder) {
		this.byteOrder = byteOrder;
		return this.self();
	}

	protected final ByteOrder getByteOrder() {
		return this.byteOrder;
	}

	private final ByteBuffer buildByteBuffer() {
		return ByteBuffer.allocate(this.length).order(this.getByteOrder());
	}

	protected final RctObject[] valueToRaw(T value, WordOrder wordOrder) {
		var buff = this.buildByteBuffer();
		this.valueToByteBuffer(buff, value);
		var b = buff.array();
		var result = new RctObject[this.length];
		result[0] = new RctObject();
		result[0].setValue(b);
		return result;
	}

	@Override
	protected final T rawToValue(RctObject[] registers) {
		if (registers.length != this.length) {
			throw new IllegalArgumentException("Registers length does not match. " //
					+ "Expected [" + this.length + "] " //
					+ "Got [" + registers.length + "] " //
					+ "for " + this.toString());
		}
		return this.registersToValue(registers);
	}

	/**
	 * Converts the {@link RctObject}s from rct protocol to the expected type.
	 * 
	 * <p>
	 * The length of the rctobject array is always 1.
	 * 
	 * @param registers the Registers
	 * @return the typed/converted value
	 */
	protected abstract T registersToValue(RctObject[] registers);

	protected final T commonRegistersToValue(RctObject[] registers, WordOrder wordOrder) {
		// fill buffer
		var buff = this.buildByteBuffer();
		buff.put(registers[0].toBytes());
		buff.rewind();
		return this.byteBufferToValue(buff);
	}
}