package io.openems.edge.rctpower.bridge.api;

public class RctFrame {
	
	private byte command;
	private byte[] id;
	private byte[] data = null;
	private byte length = 0x04;
    private int crc = 0xFFFF;
    private int numBytes = 0;

	public RctFrame(byte rctCommand, byte[] rctId) {
		command = rctCommand;
		id = rctId;
		
		calc_crc(command);
		calc_crc(length);
		for (byte b : id) {
			calc_crc(b);
		}
		if(numBytes % 2 != 0) { // if numBytes is odd, add one padding zero byte for CRC calculation
			calc_crc((byte) 0x00);
		}
	}
	
	public RctFrame(byte rctCommand, byte[] rctId, byte[] rctData) {
		command = rctCommand;
		id = rctId;
		data = rctData;
		length += (byte) rctData.length;
		
		calc_crc(command);
		calc_crc(length);
		for (byte b : id) {
			calc_crc(b);
		}
		for (byte b : data) {
			calc_crc(b);
		}
		if(numBytes % 2 != 0) { // if numBytes is odd, add one padding zero byte for CRC calculation
			calc_crc((byte) 0x00);
		}
	}	
	
	public byte getCommand() {
		return command;
	}
	
	public byte[] getID() {
		return id;
	}
	
	public byte getLength() {
		return length;
	}
	
	public byte[] getData() {
		return data;
	}
		
	public byte[] getCRC16() {			
		byte res[] = new byte[2];
		res[0] = (byte) ((crc >> 8) & 0xff);
		res[1] = (byte) (crc & 0xff);
		
		return res;
	}
	
	void calc_crc(int b) {
		numBytes++;
        for (int i = 0; i < 8; i++) {
        	boolean bit = ((b >> (7 - i) & 1) == 1);
        	boolean c15 = (((crc >> 15) & 1) == 1);
        	crc <<= 1;
        	if (c15 ^ bit) crc ^= 0x1021;
        }
	}
	
}
