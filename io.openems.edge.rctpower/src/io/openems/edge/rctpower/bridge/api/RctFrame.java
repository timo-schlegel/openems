package io.openems.edge.rctpower.bridge.api;

public class RctFrame {
	
	private byte command;
	private byte[] slaveAddress;
	private byte[] id;
	private byte[] data = null;
	private int length = 4;
    private int crc = 0xFFFF;
    private int numBytes = 0;

	public RctFrame(byte rctCommand, byte[] rctId) {
		this(rctCommand, rctId, null);
	}
	
	public RctFrame(byte rctCommand, byte[] rctId, byte[] rctData) {
	    this(rctCommand, null, rctId, rctData);
	}

	public RctFrame(byte rctCommand, byte[] rctSlaveAddress, byte[] rctId, byte[] rctData) {
		this.command = rctCommand;
		this.slaveAddress = rctSlaveAddress;
		this.id = rctId;
		this.data = rctData;

		boolean plantCommand = isPlantCommand(command);

		int payloadLength = (plantCommand ? 8 : 4) + (rctData != null ? rctData.length : 0);
		this.length = payloadLength;
		
		calc_crc(this.command);

		if (isLongCommand(this.command)) {
			calc_crc((payloadLength >> 8) & 0xFF);
			calc_crc(payloadLength & 0xFF);
		} else {
			calc_crc(payloadLength & 0xFF);
		}

		if (plantCommand) {
	        for (byte b : this.slaveAddress) {
	            calc_crc(b);
	        }
	    }

		for (byte b : id) {
			calc_crc(b);
		}

		if (this.data != null) {
			for (byte b : data) {
				calc_crc(b & 0xFF);
			}
		}

		if(this.numBytes % 2 != 0) { // if numBytes is odd, add one padding zero byte for CRC calculation
			calc_crc((byte) 0x00);
		}
	}	

	public static boolean isLongCommand(byte command) {
		return command == 0x03 || command == 0x06 || command == 0x43 || command == 0x46;
	}

	public static boolean isPlantCommand(byte command) {
	    return (command & 0x40) != 0;
	}

	public byte getCommand() {
		return this.command;
	}

	public byte[] getSlaveAddress() {
	    return this.slaveAddress;
	}

	public byte[] getID() {
		return this.id;
	}
	
	public int getLength() {
		return this.length;
		//return (byte) (this.length & 0xFF);
	}

	public byte getLengthMsb() {
		return (byte) ((this.length >> 8) & 0xFF);
	}

	public byte getLengthLsb() {
		return (byte) (this.length & 0xFF);
	}

	public byte[] getData() {
		return this.data;
	}
		
	public byte[] getCRC16() {			
		byte res[] = new byte[2];
		res[0] = (byte) ((this.crc >> 8) & 0xff);
		res[1] = (byte) (this.crc & 0xff);
		
		return res;
	}
	
	void calc_crc(int b) {
		b &= 0xFF;

		this.numBytes++;
        for (int i = 0; i < 8; i++) {
        	boolean bit = ((b >> (7 - i) & 1) == 1);
        	boolean c15 = (((this.crc >> 15) & 1) == 1);
        	this.crc <<= 1;
        	if (c15 ^ bit) {
        		this.crc ^= 0x1021;
        	}
        }
        this.crc &= 0xFFFF;
	}
	
}
