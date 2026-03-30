package io.openems.edge.rctpower.bridge.api;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class RctTCPTransport {

	private DataInputStream rctInput; // input stream
	private DataOutputStream rctOutput; // output stream
	//private BytesInputStream rctByteIn;
	//private BytesOutputStream rctByteOut; // write frames
	
	private int rctTimeout = AbstractRctPowerBridge.DEFAULT_TIMEOUT;
	private Socket rctSocket = null;
	private boolean debug = false;
	
	public RctTCPTransport(Socket socket) {
		try {
			setSocket(socket);
			socket.setSoTimeout(rctTimeout);
		} catch (IOException ex) {
			//if (Modbus.debug)
			//	System.out.println("ModbusTCPTransport::Socket invalid.");

			throw new IllegalStateException("Socket invalid.");
		}
	}
	
	public void setSocket(Socket socket) throws IOException {
		if (rctSocket != null) {
			rctSocket.close();
			rctSocket = null;
		}
		rctSocket = socket;
		//setTimeout(rctTimeout);
		
		prepareStreams(socket);
	}// setSocket
	
	public void setTimeout(int time) {
		rctTimeout = time;

		if (rctSocket != null) {
			try {
				rctSocket.setSoTimeout(time);
			} catch (SocketException e) {
				// Not sure what to do.
				return;
			}
		}
	}	
	
	public void close() throws IOException {
		rctInput.close();
		rctOutput.close();
		rctSocket.close();
	}// close
	
	public void flushInputStream() {
		try {
			rctInput.skipNBytes(rctInput.available());
		} catch (IOException e) {
			// do nothing
			
			/*
			 * TODO: REMOVE logging !!!
			 */
			System.out.println("fooo: "+e.getMessage());
		}
	}
	
	/**
	 * Prepares the input and output streams of this <tt>RctTCPTransport</tt>
	 * instance based on the given socket.
	 * 
	 * @param socket
	 *            the socket used for communications.
	 * @throws IOException
	 *             if an I/O related error occurs.
	 */
	private void prepareStreams(Socket socket) throws IOException {

		/*
		 * Close any open streams if I'm being called because a new socket was
		 * set to handle this transport.
		 */
		try {
			if (rctInput != null)
				rctInput.close();

			if (rctOutput != null)
				rctOutput.close();
		} catch (IOException x) {
			// Do nothing.
		}

		rctInput = new DataInputStream(new BufferedInputStream(
				socket.getInputStream()));
				
		rctOutput = new DataOutputStream(new BufferedOutputStream(
				socket.getOutputStream()));

	}
	
	public void writeMessage(RctFrame frame) throws IOException {
		try {			
			rctOutput.write((byte)0x2B); // START
			rctOutput.write(frame.getCommand());
			rctOutput.write(frame.getLength());
			rctOutput.write(frame.getID());
			
			if(frame.getLength()>4) {
				rctOutput.write(frame.getData());
			}
			
			byte crc[] = frame.getCRC16();
			rctOutput.write(crc[0]);
			rctOutput.write(crc[1]);
			rctOutput.flush();
			
		} catch (Exception ex) {
			throw new IOException("I/O exception - failed to write.");
		}
	}
	
	public RctResponse readResponse() throws IOException {

		try {

			final int FRAME_LENGTH_HEADER = 1; // Length of the header
			final int FRAME_LENGTH_COMMAND = 1; // Length of a command
			final int FRAME_LENGTH_LENGTH = 2; // Length of the length information
			final int FRAME_HEADER_WITH_LENGTH = FRAME_LENGTH_HEADER + FRAME_LENGTH_COMMAND + FRAME_LENGTH_LENGTH; // Length of a frame, contains 1 byte header, 1 byte command and 2 bytes length
			final int FRAME_LENGTH_CRC16 = 2; // Length of the CRC16 checkum
			
			final int BUFFER_LEN_COMMAND = 2; // Amount of bytes we need to have a command
						
			RctResponse response = null;

			synchronized (rctInput) {
				
				// set initially to the minimum length a frame header (i.e. everything before the data) can be.
			    // 1 byte start, 1 byte command, 1 byte length, no address, 4 byte ID
			    int frame_header_length = 1 + 1 + 1 + 0 + 4;
			    			    
				var buffer = ByteBuffer.allocate(1024);
				int frame_length = 0;
				int length = 0;
				int data_length = 0;
				byte address[] = new byte[4];
				int address_idx = 0;
				byte id[] = new byte[4];
				int oid_idx = 0;
				boolean complete = false;
				boolean escaping = false;
				byte cmd = 0;
				byte b;		
					
				while(true) {	
					b = rctInput.readByte();
					if(debug) System.out.println("Read: "+String.format("%02X", b));
					
					// sync to start_token
					if(b == 0x2B && !escaping) {
						if(debug) System.out.println("    start token found");
						if(buffer.position() != 0) {
							if(debug) System.out.println("    buffer is not empty, reset buffer");
							buffer = ByteBuffer.allocate(1024);
							
							// re-inizalize variables
							frame_header_length = 1 + 1 + 1 + 0 + 4;
							frame_length = 0;
							address = new byte[4];
							address_idx = 0;
							id = new byte[4];
							oid_idx = 0;
							length = 0;
							data_length = 0;
							complete = false;
							cmd = 0;
						}
						buffer.put(b);
						continue;
					}
										
					// escaping
					// TODO: verify functionality
					if(escaping) {
						if(debug) System.out.println("    resetting escape");
						escaping = false;
					} else {
						if(b == 0x2D) {
							if(debug) System.out.println("    setting escape and ignoring escape-byte");
							// set the escape mode and ignore the byte
							escaping = true;
							continue;
						}
					}
					
					buffer.put(b);
					if(debug) System.out.println("    adding to buffer");
					
					int blen = buffer.position();					
					
					if(blen == BUFFER_LEN_COMMAND) {
						cmd = b;
						
						// plant command
						if((cmd & 0x40)>0) {
							frame_header_length += 4;
							if(debug) System.out.println("      plant frame, extending header length by 4 to "+frame_header_length);
						}
						
						// long command
						if(cmd == 0x03 || cmd == 0x06 || cmd == 0x43 || cmd == 0x46) {
							frame_header_length += 1;
							if(debug) System.out.println("      long cmd, extending header length by 1 to "+frame_header_length);
						}
							
					}else if(blen == frame_header_length) {
						if(debug) System.out.println("      buffer length "+blen+" indicates that it contains entire header");
						
						// long command
						if(cmd == 0x03 || cmd == 0x06 || cmd == 0x43 || cmd == 0x46) {
							/*
							 *  TODO: VERIFY CORRECT MATH !!!!!
							 */
							System.out.println("      Recevied a LONG COMMAND - NOT YET SUPPORTED");
							length = (length << 8) + (buffer.get(2) & 0xff);
							length = (length << 8) + (buffer.get(3) & 0xff);
							data_length = length-4;
							address_idx = 4;
						} else {
							length = buffer.get(2);
							data_length = length-4;
							address_idx = 3;
						}
						
						// plant command
						if((cmd & 0x40)>0) {
							// length field includes address and id length == 8 bytes
							frame_length = (frame_header_length - 8) + length + FRAME_LENGTH_CRC16;
							buffer.get(address_idx, address, 0, 4);
				            oid_idx = address_idx + 4;
						} else {
							// length field includes id length == 4 bytes
							frame_length = (frame_header_length - 4) + length + FRAME_LENGTH_CRC16;
							oid_idx = address_idx;
						}
											
						if(debug) System.out.println("      data length: "+data_length+" bytes, frame_length: "+frame_length+" bytes");
						
						buffer.get(oid_idx, id, 0, 4);						

						if(debug) System.out.println("      oid index: "+oid_idx+", OID: "+String.format("0x%02X%02X%02X%02X", id[0], id[1], id[2], id[3]));
						
					}else if(frame_length > 0 && blen == frame_length) {
						if(debug) System.out.println("      buffer contains full frame");
						if(debug) System.out.println("      buffer: "+buffer.toString());
		                complete=true;

		                byte crc[] = new byte[2];
		                buffer.get(buffer.position()-2, crc, 0, 2);

		                byte data[] = new byte[data_length];
		                buffer.get(oid_idx+4, data, 0, data_length);
		                
		                response = new RctResponse(cmd, id, data);

		                //verify crc
		                if(!Arrays.equals(response.getCRC16(), crc)) {
		                	byte getcrc[] = response.getCRC16();
		                	System.out.println("      wrong crc. discarding frame.");
		                	System.out.println("      received crc: "+String.format("0x%02X%02X",crc[0],crc[1])+" != calculcate crc: "+String.format("0x%02X%02X",getcrc[0],getcrc[1]));
		                	System.out.print("      buffer:");
		                	for(int xi=0; xi<buffer.position(); xi++){
		                		System.out.print(" "+String.format("0x%02X",buffer.get(xi)));
		                	}
		                	System.out.println();
		                	response=null;
		                }
		                
		                if(debug) System.out.println("unread bytes: "+rctInput.available());
		                
		                break;
					}else if(frame_length > 0 && blen > frame_length) {
						throw new IOException(
								"Frame consumed more than it should.");
					}
				}

			}
			return response;
		} catch (SocketTimeoutException ex) {
			throw new IOException("Timeout reading response");
		} catch (EOFException ex) {
			throw new IOException("Premature end of stream (Message truncated).");
		} catch (IOException ex) {
			throw new IOException("I/O exception - failed to read");			
		} catch (Exception ex) {
			throw new IOException("I/O exception - failed to read.");
		}
	}	
}
