package io.openems.edge.rctpower.bridge.api;

import java.nio.ByteBuffer;

public class RctObject {

	private byte data[];
	
	/**
	 * Sets the content of this <tt>Register</tt> from the given unsigned 16-bit
	 * value (unsigned short).
	 * 
	 * @param v
	 *            the value as unsigned short (<tt>int</tt>).
	 */
	public void setValue(int v) {
		
	}

	/**
	 * Sets the content of this register from the given signed 16-bit value
	 * (short).
	 * 
	 * @param s
	 *            the value as <tt>short</tt>.
	 */
	public void setValue(short s) {
		
	}

	/**
	 * Sets the content of this register from the given raw bytes.
	 * 
	 * @param bytes
	 *            the raw data as <tt>byte[]</tt>.
	 */
	public void setValue(byte[] bytes) {
		this.data = bytes;
	}
	
	/**
	 * Returns the value of this <tt>RctObject</tt>. The value is stored as
	 * <tt>int</tt> but should be treated like a 8-bit word.
	 * 
	 * @return the value as <tt>int</tt>.
	 */
	public int getValue() {
		return ByteBuffer.wrap(this.data).get();
	}

	/**
	 * Returns the content of this <tt>RctObject</tt> as unsigned 16-bit value
	 * (unsigned short).
	 * 
	 * @return the content as unsigned short (<tt>int</tt>).
	 */
	public int toUnsignedShort() {
		return 0;
		
	}

	/**
	 * Returns the content of this <tt>RctObject</tt> as signed 16-bit value
	 * (short).
	 * 
	 * @return the content as <tt>short</tt>.
	 */
	public short toShort() {
		return 0;
		
	}

	/**
	 * Returns the content of this <tt>RctObject</tt> as bytes.
	 * 
	 * @return a <tt>byte[]</tt>.
	 */
	public byte[] toBytes() {
		return data;
	}

}