package io.openems.edge.rctpower.bridge.api;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

public class RctTCPConnection {

	private Socket rctSocket;
	private int rctTimeout = AbstractRctPowerBridge.DEFAULT_TIMEOUT;	//ms
	private boolean rctConnected;
	
	private InetAddress rctAddress;
	private int rctPort = AbstractRctPowerBridge.DEFAULT_PORT;	
	
	private RctTCPTransport rctTCPTransport;
	
	public RctTCPConnection(InetAddress adr) {
		rctAddress = adr;
	}	
	
	private void prepareTransport() throws IOException {
		if (rctTCPTransport == null) {
			rctTCPTransport = new RctTCPTransport(rctSocket);
		} else {
			rctTCPTransport.setSocket(rctSocket);
		}
	}// prepareIO	
	
	/**
	 * Opens this <tt>RctTCPConnection</tt>.
	 * 
	 * @throws Exception
	 *             if there is a network failure.
	 */
	public synchronized void connect() throws Exception {
		if (! isConnected()) {
			//if (Modbus.debug)
			//	System.out.println("connect()");
			
			rctSocket = new Socket(rctAddress, rctPort);
			rctSocket.setReuseAddress(true);
			rctSocket.setSoLinger(true, 1);
			rctSocket.setKeepAlive(true);
			
			setTimeout(rctTimeout);
			prepareTransport();
			
			rctConnected = true;
		}
	}// connect	
	
	/**
	 * Tests if this <tt>RctTCPConnection</tt> is connected.
	 * 
	 * @return <tt>true</tt> if connected, <tt>false</tt> otherwise.
	 */
	public synchronized boolean isConnected() {
		return rctConnected;
	}// isConnected
	
	/**
	 * Closes this <tt>TCPMasterConnection</tt>.
	 */
	public void close() {
		if (rctConnected) {
			try {
				rctTCPTransport.close();
			} catch (IOException ex) {
				//if (Modbus.debug)
				//	System.out.println("close()");
			}
			rctConnected = false;
		}
	}// close
	
	/**
	 * Returns the <tt>RctTCPTransport</tt> associated with this
	 * <tt>RctTCPConnection</tt>.
	 * 
	 * @return the connection's <tt>RctTCPTransport</tt>.
	 */
	public RctTCPTransport getRctTCPTransport() {
		return rctTCPTransport;
	}// getRctTCPTransport	
	
	/**
	 * Returns the timeout for this <tt>RctTCPConnection</tt>.
	 * 
	 * @return the timeout as <tt>int</tt>.
	 */
	public int getTimeout() {
		return rctTimeout;
	}// getTimeout

	/**
	 * Sets the timeout for this <tt>RctTCPConnection</tt>.
	 * 
	 * @param timeout
	 *            the timeout as <tt>int</tt>.
	 */
	public void setTimeout(int timeout) {
		rctTimeout = timeout;
		try {
			rctSocket.setSoTimeout(rctTimeout);
		} catch (IOException ex) {
			// handle?
		}
	}// setTimeout
	
	/**
	 * Returns the destination port of this <tt>TCPMasterConnection</tt>.
	 * 
	 * @return the port number as <tt>int</tt>.
	 */
	public int getPort() {
		return rctPort;
	}// getPort

	/**
	 * Sets the destination port of this <tt>TCPMasterConnection</tt>. The
	 * default is defined as <tt>Modbus.DEFAULT_PORT</tt>.
	 * 
	 * @param port
	 *            the port number as <tt>int</tt>.
	 */
	public void setPort(int port) {
		rctPort = port;
	}// setPort
}
