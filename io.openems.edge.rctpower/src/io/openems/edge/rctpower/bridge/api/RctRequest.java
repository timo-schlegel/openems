package io.openems.edge.rctpower.bridge.api;

public class RctRequest extends RctFrame {

	public RctRequest(byte rctCommand, byte[] rctId) {
		super(rctCommand, rctId);
	}
	
	public RctRequest(byte rctCommand, byte[] rctId, byte[] rctData) {
		super(rctCommand, rctId, rctData);
	}	

	public RctRequest(byte rctCommand, byte[] rctSlaveAddress, byte[] rctId, byte[] rctData) {
	    super(rctCommand, rctSlaveAddress, rctId, rctData);
	}
}
