package io.openems.edge.rctpower.bridge.api;

public class RctResponse extends RctFrame {

	public RctResponse(byte rctCommand, byte[] rctId, byte[] rctData) {
		super(rctCommand, rctId, rctData);
	}

	public RctResponse(byte rctCommand, byte[] rctSlaveAddress, byte[] rctId, byte[] rctData) {
	    super(rctCommand, rctSlaveAddress, rctId, rctData);
	}
}
