package io.openems.edge.rctpower.bridge.api;

import java.io.IOException;
import java.util.Arrays;

import io.openems.common.exceptions.OpenemsException;

public class RctTransaction {

		private RctTCPConnection rctTCPConnection;
		private RctTCPTransport rctIO;
		private RctFrame rctRequest;
		private RctResponse rctResponse;
		private int rctRetries = 2;

		public RctTransaction(RctTCPConnection con, RctFrame req) {
			this.rctTCPConnection = con;
			this.rctIO = con.getRctTCPTransport(); 
			this.rctRequest = req;
		}
		
		public RctFrame getRequest() {
			return rctRequest;
		}

		public RctResponse getResponse() {
			return rctResponse;
		}
		
		public int getRetries() {
			return rctRetries;
		}
		
		public void setRetries(int num) {
			rctRetries = num;
		}
		
		public void execute() throws OpenemsException {
			
			if (rctRequest == null || rctTCPConnection == null)
				throw new OpenemsException("Invalid request or connection");
		
			/*
			 * Automatically re-connect if disconnected.
			 */
			if (!rctTCPConnection.isConnected()) {
				try {
					rctTCPConnection.connect();
				} catch (Exception ex) {
					throw new OpenemsException("Connection failed.");
				}
			}
		
			/*
			 * Try sending the message up to m_Retries time. Note that the message
			 * is read immediately after being written, with no flushing of buffers.
			 */
			int retryCounter = 0;
			int retryLimit = (rctRetries > 0 ? rctRetries:1);
			
			while (retryCounter < retryLimit) {
				try {
					synchronized (rctIO) {

						/*
						 * Flush RX buffer as we also get responses for other clients
						 * (e.g. RCT Power App, RCT Power Portal, ...)
						 * 
						 * Reason: We connect to the control engine via an shared UART interface
						 * (using a embedded HF-A21 UART to Wi-Fi/Eth Chip).
						 * For details, refer to https://github.com/evcc-io/evcc/pull/14774
						 */
						rctIO.flushInputStream();
						
						rctIO.writeMessage(rctRequest);
						rctResponse = null;
						
						do {
							rctResponse = rctIO.readResponse();
						} while (rctResponse == null || !Arrays.equals(rctRequest.getID(), rctResponse.getID()));

						/*
						 * Both methods were successful, so the transaction must
						 * have been executed.
						 */
						break;
					}
				} catch (IOException ex) {
					if (rctTCPConnection.isConnected()) {
						rctTCPConnection.close();
						throw new OpenemsException("Connection lost."+ex.getMessage());
					}
					if (retryCounter >= retryLimit) {
						throw new OpenemsException(
								"Executing transaction failed (tried " + rctRetries
										+ " times)");
					} else {
						retryCounter++;
						continue;
					}
				}
			}		
		}
		
		public void executeWriteOnly() throws OpenemsException {
			
			if (rctRequest == null || rctTCPConnection == null)
				throw new OpenemsException("Invalid request or connection");
		
			/*
			 * Automatically re-connect if disconnected.
			 */
			if (!rctTCPConnection.isConnected()) {
				try {
					rctTCPConnection.connect();
				} catch (Exception ex) {
					throw new OpenemsException("Connection failed.");
				}
			}
		
			/*
			 * Try sending the message up to m_Retries time. Note that the message
			 * is read immediately after being written, with no flushing of buffers.
			 */
			int retryCounter = 0;
			int retryLimit = (rctRetries > 0 ? rctRetries:1);
			
			while (retryCounter < retryLimit) {
				try {
					synchronized (rctIO) {

						// write message and do not expect any response
						rctIO.writeMessage(rctRequest);
						rctResponse = null;
						
						break;
					}
				} catch (IOException ex) {
					if (rctTCPConnection.isConnected()) {
						rctTCPConnection.close();
						throw new OpenemsException("Connection lost."+ex.getMessage());
					}
					if (retryCounter >= retryLimit) {
						throw new OpenemsException(
								"Executing transaction failed (tried " + rctRetries
										+ " times)");
					} else {
						retryCounter++;
						continue;
					}
				}
			}
		
		}		
	
}
