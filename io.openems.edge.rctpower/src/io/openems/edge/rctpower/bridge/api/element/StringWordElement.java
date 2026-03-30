package io.openems.edge.rctpower.bridge.api.element;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import io.openems.common.types.OpenemsType;

/**
 * An StringWordElement represents a String value. Each Register (= 1 byte)
 * represents one character.
 */
public class StringWordElement extends AbstractMultipleWordsElement<StringWordElement, String> {

	public StringWordElement(int startAddress, int length) {
		super(OpenemsType.STRING, startAddress, length);
	}

	@Override
	protected String byteBufferToValue(ByteBuffer buff) {
		var src = buff.array();
		var out = new byte[src.length];
		for (int i = 0; i < src.length; i++) {
			out[i] = src[i];
		}
		
		return new String(tidyUp(out)).trim();
	}

	@Override
	protected StringWordElement self() {
		return this;
	}

	@Override
	protected void valueToByteBuffer(ByteBuffer buff, String value) {
		buff.put(value.getBytes(StandardCharsets.US_ASCII));
	}

	private static byte[] tidyUp(byte[] array) {
		for (var i = 0; i < array.length; i++) {
			array[i] = tidyUp(array[i]);
		}
		return array;
	}

	private static byte tidyUp(byte b) {
		if (b == 0) {
			b = 32;// replace '0' with ASCII space
		}
		return b;
	}

}