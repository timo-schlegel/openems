package io.openems.edge.rctpower.bridge.api;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RctTCPTransport {

	private DataInputStream rctInput; // input stream
	private DataOutputStream rctOutput; // output stream
	//private BytesInputStream rctByteIn;
	//private BytesOutputStream rctByteOut; // write frames
	
	private int rctTimeout = AbstractRctPowerBridge.DEFAULT_TIMEOUT;
	private Socket rctSocket = null;
	private boolean debug = false;

	private static final int START_TOKEN = 0x2B;
	private static final int ESCAPE_TOKEN = 0x2D;
	private static final int MAX_FRAME_LEN = 2048;
	private static final int CRC_LEN = 2;

	private final byte[] parserFrame = new byte[MAX_FRAME_LEN];
	private int parserPos = 0;
	private int parserHeaderLen = 3;
	private int parserFrameLen = -1;
	private int parserLength = 0;
	private int parserDataLength = 0;
	private int parserOidIdx = 0;
	private boolean parserEscaping = false;
	private boolean parserSynced = false;
	private byte parserCmd = 0;
	
	private static final int RAW_TRACE_LEN = 128;
	private final byte[] rawTrace = new byte[RAW_TRACE_LEN];
	private int rawTracePos = 0;
	private long rawTraceBytesSeen = 0;
	
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

		this.resetParser();
	}
	
	public void writeMessage(RctFrame frame) throws IOException {
		try {
			rctOutput.write(this.encodeMessage(frame));
			rctOutput.flush();
		} catch (Exception ex) {
			throw new IOException("I/O exception - failed to write.");
		}
	}

	public void writeMessages(List<? extends RctFrame> frames) throws IOException {
		try {
			for (var frame : frames) {
				rctOutput.write(this.encodeMessage(frame));
			}
			rctOutput.flush();
		} catch (Exception ex) {
			throw new IOException("I/O exception - failed to write batch.", ex);
		}
	}

	private byte[] encodeMessage(RctFrame frame) throws IOException {
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();

			buffer.write((byte) START_TOKEN);
			writeEscaped(buffer, frame.getCommand());

			if (RctFrame.isLongCommand(frame.getCommand())) {
				writeEscaped(buffer, frame.getLengthMsb());
			    writeEscaped(buffer, frame.getLengthLsb());
			} else {
			    writeEscaped(buffer, frame.getLengthLsb());
			}

			if (RctFrame.isPlantCommand(frame.getCommand())) {
			    byte[] slaveAddress = frame.getSlaveAddress();
			    if (slaveAddress == null || slaveAddress.length != 4) {
			        throw new IOException("Invalid plant frame: missing 4-byte slave address");
			    }

			    for (byte b : slaveAddress) {
			        writeEscaped(buffer, b);
			    }
			}

			for (byte b : frame.getID()) {
			    writeEscaped(buffer, b);
			}

			byte[] data = frame.getData();

			if (data != null && data.length > 0) {
			    for (byte b : data) {
			        writeEscaped(buffer, b);
			    }
			}

			byte[] crc = frame.getCRC16();
			writeEscaped(buffer, crc[0]);
			writeEscaped(buffer, crc[1]);

			return buffer.toByteArray();

		} catch (Exception ex) {
			throw new IOException("I/O exception - failed to encode frame.", ex);
		}
	}

	private static void writeEscaped(ByteArrayOutputStream buffer, byte b) {
	    int v = b & 0xFF;

	    if (v == START_TOKEN || v == ESCAPE_TOKEN) {
	        buffer.write((byte) ESCAPE_TOKEN);
	    }

	    buffer.write(b);
	}

	public List<RctFrame> readAvailableFramesBlocking() throws IOException {
		var frames = new ArrayList<RctFrame>();
		final byte[] chunk = new byte[512];

		synchronized (this.rctInput) {
			try {
				if (this.rctSocket != null) {
					this.rctSocket.setSoTimeout(this.rctTimeout);
				}

				// Block until at least one byte arrives.
				int n = this.rctInput.read(chunk);
				if (n < 0) {
					throw new EOFException();
				}
				this.parseBytes(chunk, n, frames);

				// Drain everything that is already buffered without waiting long.
				while (this.rctInput.available() > 0) {
					int available = Math.min(chunk.length, this.rctInput.available());
					n = this.rctInput.read(chunk, 0, available);
					if (n < 0) {
						throw new EOFException();
					}
					this.parseBytes(chunk, n, frames);
				}

				return frames;

			} catch (SocketTimeoutException e) {
				return frames;
			}
		}
	}

	private void parseBytes(byte[] bytes, int len, List<RctFrame> frames) throws IOException {
		for (int i = 0; i < len; i++) {
			this.parseByte(bytes[i], frames);
		}
	}

	private void parseByte(byte raw, List<RctFrame> frames) throws IOException {
		this.traceRaw(raw);
		int b = raw & 0xFF;

		if (!this.parserSynced) {
			if (b == START_TOKEN) {
				this.resetParser();
				this.parserFrame[0] = raw;
				this.parserPos = 1;
				this.parserSynced = true;
			}
			return;
		}

		// Resync on new unescaped start token.
		if (b == START_TOKEN && !this.parserEscaping) {
			this.resetParser();
			this.parserFrame[0] = raw;
			this.parserPos = 1;
			this.parserSynced = true;
			return;
		}

		if (this.parserEscaping) {
			this.parserEscaping = false;

			if (b != START_TOKEN && b != ESCAPE_TOKEN) {
		        this.logInvalidEscape(raw);
		        this.resetParser();
		        return;
		    }
		} else if (b == ESCAPE_TOKEN) {
			this.parserEscaping = true;
			return;
		}

		if (this.parserPos >= MAX_FRAME_LEN) {
			this.resetParser();
			return;
		}

		this.parserFrame[this.parserPos++] = raw;

		if (this.parserPos == 2) {
			this.parserCmd = raw;
			this.parserHeaderLen = RctFrame.isLongCommand(this.parserCmd) ? 4 : 3;
		}

		if (this.parserFrameLen < 0 && this.parserPos == this.parserHeaderLen) {
			if (!this.prepareFrameLength()) {
				this.resetParser();
				return;
			}
		}

		if (this.parserFrameLen > 0 && this.parserPos == this.parserFrameLen) {
			var frame = this.buildFrameFromParserBuffer();
			if (frame != null) {
				frames.add(frame);
			}
			this.resetParser();
		}
	}

	private void traceRaw(byte raw) {
	    this.rawTrace[this.rawTracePos] = raw;
	    this.rawTracePos = (this.rawTracePos + 1) % RAW_TRACE_LEN;
	    this.rawTraceBytesSeen++;
	}

	private void logRawTrace() {
	    int count = (int) Math.min(this.rawTraceBytesSeen, RAW_TRACE_LEN);

	    System.out.print("      raw trace last " + count + " bytes:");

	    for (int i = 0; i < count; i++) {
	        int idx = (this.rawTracePos - count + i + RAW_TRACE_LEN) % RAW_TRACE_LEN;
	        System.out.print(" " + String.format("0x%02X", this.rawTrace[idx]));
	    }

	    System.out.println();
	}

	private void logInvalidEscape(byte raw) {
	    System.out.println("      invalid escape sequence. discarding frame.");
	    System.out.println("      escape byte 0x2D followed by 0x" + String.format("%02X", raw));
	    this.logRawTrace();
	    //this.logParserState();
	}

	private boolean prepareFrameLength() {
		boolean longCommand = RctFrame.isLongCommand(this.parserCmd);
		boolean plantCommand = (this.parserCmd & 0x40) != 0;

		if (longCommand) {
			this.parserLength = ((this.parserFrame[2] & 0xFF) << 8) | (this.parserFrame[3] & 0xFF);
		} else {
			this.parserLength = this.parserFrame[2] & 0xFF;
		}

		int payloadStart = this.parserHeaderLen;

		if (plantCommand) {
			// Plant payload: address(4) + oid(4) + data
			if (this.parserLength < 8) {
				return false;
			}
			this.parserOidIdx = payloadStart + 4;
			this.parserDataLength = this.parserLength - 8;
		} else {
			// Normal payload: oid(4) + data
			if (this.parserLength < 4) {
				return false;
			}
			this.parserOidIdx = payloadStart;
			this.parserDataLength = this.parserLength - 4;
		}

		this.parserFrameLen = this.parserHeaderLen + this.parserLength + CRC_LEN;

		return this.parserFrameLen > 0 && this.parserFrameLen <= MAX_FRAME_LEN;
	}

	private RctFrame buildFrameFromParserBuffer() {
		boolean plantCommand = (this.parserCmd & 0x40) != 0;

		byte[] id = Arrays.copyOfRange(this.parserFrame, this.parserOidIdx, this.parserOidIdx + 4);
		byte[] data = this.parserDataLength > 0
				? Arrays.copyOfRange(this.parserFrame, this.parserOidIdx + 4, this.parserOidIdx + 4 + this.parserDataLength)
				: new byte[0];

		RctResponse response;

		if (plantCommand) {
		    byte[] address = Arrays.copyOfRange(
		            this.parserFrame,
		            this.parserHeaderLen,
		            this.parserHeaderLen + 4
		    );

		    response = new RctResponse(this.parserCmd, address, id, data);
		} else {
		    response = new RctResponse(this.parserCmd, id, data);
		}

		byte crc0 = this.parserFrame[this.parserPos - 2];
		byte crc1 = this.parserFrame[this.parserPos - 1];
		byte[] expectedCrc = response.getCRC16();

		if (expectedCrc[0] != crc0 || expectedCrc[1] != crc1) {
			this.logCrcMismatch(crc0, crc1, expectedCrc);
			return null;
		}

		if (plantCommand) {
	        // gültig empfangen, aber aktuell nicht als normale Response weitergeben
	        return null;
	    }

		return response;
	}

	private void resetParser() {
		this.parserPos = 0;
		this.parserHeaderLen = 3;
		this.parserFrameLen = -1;
		this.parserLength = 0;
		this.parserDataLength = 0;
		this.parserOidIdx = 0;
		this.parserEscaping = false;
		this.parserSynced = false;
		this.parserCmd = 0;
	}

	private void logCrcMismatch(byte crc0, byte crc1, byte[] expectedCrc) {
		System.out.println("      wrong crc. discarding frame.");
		System.out.println("      received crc: " + String.format("0x%02X%02X", crc0, crc1)
				+ " != calculated crc: " + String.format("0x%02X%02X", expectedCrc[0], expectedCrc[1]));

		System.out.print("      buffer:");
		for (int i = 0; i < this.parserPos; i++) {
			System.out.print(" " + String.format("0x%02X", this.parserFrame[i]));
		}
		System.out.println();

		this.logRawTrace();

		System.out.println("      parser state:"
				+ " cmd=0x" + String.format("%02X", this.parserCmd)
				+ " length=" + this.parserLength
				+ " dataLength=" + this.parserDataLength
				+ " headerLen=" + this.parserHeaderLen
				+ " frameLen=" + this.parserFrameLen
				+ " pos=" + this.parserPos
				+ " oidIdx=" + this.parserOidIdx);

		if (this.parserPos >= this.parserOidIdx + 4) {
			int oid = ((this.parserFrame[this.parserOidIdx] & 0xFF) << 24)
					| ((this.parserFrame[this.parserOidIdx + 1] & 0xFF) << 16)
					| ((this.parserFrame[this.parserOidIdx + 2] & 0xFF) << 8)
					| (this.parserFrame[this.parserOidIdx + 3] & 0xFF);

			System.out.println("      parsed oid (best effort): 0x" + String.format("%08X", oid));
		}
	}

	// Obsolete
	public RctResponse readResponse(int timeoutMs) throws IOException {
		System.out.println("********** OBSOLETE RctTCPTransport::readResponse()");
		return null;
		/*
		final int previousTimeout = this.rctTimeout;
		final long deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L;

		try {
			return this.readResponseUntil(deadlineNs);

		} catch (SocketTimeoutException ex) {
			throw new IOException("Timeout reading response", ex);

		} catch (EOFException ex) {
			throw new IOException("Premature end of stream (Message truncated).", ex);

		} catch (IOException ex) {
			throw ex;

		} catch (Exception ex) {
			throw new IOException("I/O exception - failed to read.", ex);

		} finally {
			if (this.rctSocket != null) {
				try {
					this.rctSocket.setSoTimeout(previousTimeout);
				} catch (SocketException ignore) {
					// ignore
				}
			}
		}
		*/
	}

	private static int remainingMillis(long deadlineNs) {
		return (int) Math.max(0, (deadlineNs - System.nanoTime()) / 1_000_000L);
	}

	private static int bytesToInt(byte[] id) {
		if (id == null || id.length != 4) {
			return Integer.MIN_VALUE;
		}
		return ((id[0] & 0xFF) << 24)
				| ((id[1] & 0xFF) << 16)
				| ((id[2] & 0xFF) << 8)
				| (id[3] & 0xFF);
	}

	// Obsolete
	public Map<Integer, RctResponse> readResponsesUntil(Set<Integer> expectedOids, long deadlineNs)
			throws IOException {
		System.out.println("********** OBSOLETE RctTCPTransport::readResponseUntil()");
		return null;

		/*
		Map<Integer, RctResponse> responses = new HashMap<>();

		while (responses.size() < expectedOids.size()) {
			try {
				var response = this.readResponseUntil(deadlineNs);
				int oid = bytesToInt(response.getID());

				if (expectedOids.contains(oid)) {
					responses.put(oid, response);
				}

			} catch (SocketTimeoutException e) {
				break; // partial result is OK
			}
		}

		return responses;
		*/
	}

	// Obsolete
	public RctResponse readResponseUntil(long deadlineNs) throws IOException {
		try {
			final int MAX_FRAME_LEN = 1024;
			final int FRAME_LENGTH_CRC16 = 2;
			final int START_TOKEN = 0x2B;
			final int ESCAPE_TOKEN = 0x2D;

			synchronized (rctInput) {
				final byte[] frame = new byte[MAX_FRAME_LEN];
				final byte[] chunk = new byte[64];

				int pos = 0; // decoded/unescaped bytes in frame[]
				int frameHeaderLength = 1 + 1 + 1; // start + cmd + len
				int frameLength = -1; // decoded frame length incl. crc
				int length = 0;
				int dataLength = 0;
				int addressIdx = 0;
				int oidIdx = 0;

				boolean escaping = false;
				boolean synced = false;
				byte cmd = 0;

				while (true) {
					// 1) Search start token bytewise
					if (!synced) {
						int remainingMs = remainingMillis(deadlineNs);
						if (remainingMs <= 0) {
							throw new SocketTimeoutException("Timeout reading response");
						}
						if (this.rctSocket != null) {
							this.rctSocket.setSoTimeout(Math.max(1, remainingMs));
						}

						byte b = rctInput.readByte();
						if ((b & 0xFF) == START_TOKEN) {
							frame[0] = b;
							pos = 1;
							synced = true;

							frameHeaderLength = 1 + 1 + 1;
							frameLength = -1;
							length = 0;
							dataLength = 0;
							addressIdx = 0;
							oidIdx = 0;
							escaping = false;
							cmd = 0;
						}
						continue;
					}

					// 2) After sync: block-read as much as is already available
					int remainingMs = remainingMillis(deadlineNs);
					if (remainingMs <= 0) {
						throw new SocketTimeoutException("Timeout reading response");
					}
					if (this.rctSocket != null) {
						this.rctSocket.setSoTimeout(Math.max(1, remainingMs));
					}

					/*
					int n;
					int available = rctInput.available();
					if (available > 0) {
						n = rctInput.read(chunk, 0, Math.min(chunk.length, available));
					} else {
						chunk[0] = rctInput.readByte(); // block for one byte if nothing buffered
						n = 1;
					}
					*/
					int n;
					chunk[0] = rctInput.readByte();
					n = 1;

					if (n < 0) {
						throw new EOFException();
					}

					for (int i = 0; i < n; i++) {
						byte b = chunk[i];

						// Resync on new start token, unless escaped
						if ((b & 0xFF) == START_TOKEN && !escaping) {
							frame[0] = b;
							pos = 1;
							frameHeaderLength = 1 + 1 + 1;
							frameLength = -1;
							length = 0;
							dataLength = 0;
							addressIdx = 0;
							oidIdx = 0;
							escaping = false;
							cmd = 0;
							continue;
						}

						// Escape handling
						if (escaping) {
							escaping = false;
						} else if ((b & 0xFF) == ESCAPE_TOKEN) {
							escaping = true;
							continue;
						}

						if (pos >= MAX_FRAME_LEN) {
							throw new IOException("Frame exceeded maximum buffer length.");
						}

						frame[pos++] = b;

						// command byte available
						if (pos == 2) {
							cmd = b;

							if (RctFrame.isLongCommand(cmd)) {
								frameHeaderLength = 1 + 1 + 2; // start + cmd + len(2)
							} else {
								frameHeaderLength = 1 + 1 + 1; // start + cmd + len(1)
							}
						}

						// full header decoded
						if (frameLength < 0 && pos == frameHeaderLength) {
							final boolean longCommand = RctFrame.isLongCommand(cmd);
							final boolean plantCommand = (cmd & 0x40) != 0;

							if (longCommand) {
								length = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
								dataLength = length - 4;
								addressIdx = 4;
							} else {
								length = frame[2] & 0xFF;
								dataLength = length - 4;
								addressIdx = 3;
							}

							if (dataLength < 0) {
								// Invalid/foreign/noisy frame. Drop current frame and resync on next start token.
								synced = false;
								pos = 0;
								frameHeaderLength = 1 + 1 + 1;
								frameLength = -1;
								length = 0;
								dataLength = 0;
								addressIdx = 0;
								oidIdx = 0;
								escaping = false;
								cmd = 0;
								continue;
							}

							if (plantCommand) {
								// length contains address + oid
								oidIdx = addressIdx + 4;
							} else {
								// length contains oid
								oidIdx = addressIdx;
							}

							frameLength = frameHeaderLength + length + FRAME_LENGTH_CRC16;

							if (frameLength <= 0 || frameLength > MAX_FRAME_LEN) {
								// Invalid/foreign/noisy frame. Drop current frame and resync on next start token.
								synced = false;
								pos = 0;
								frameHeaderLength = 1 + 1 + 1;
								frameLength = -1;
								length = 0;
								dataLength = 0;
								addressIdx = 0;
								oidIdx = 0;
								escaping = false;
								cmd = 0;
								continue;
							}
						}

						// full decoded frame available
						if (frameLength > 0 && pos == frameLength) {
							byte[] id = Arrays.copyOfRange(frame, oidIdx, oidIdx + 4);
							byte[] data = dataLength > 0
									? Arrays.copyOfRange(frame, oidIdx + 4, oidIdx + 4 + dataLength)
									: new byte[0];

							RctResponse response = new RctResponse(cmd, id, data);

							byte crc0 = frame[pos - 2];
							byte crc1 = frame[pos - 1];
							byte[] expectedCrc = response.getCRC16();

							if (expectedCrc[0] != crc0 || expectedCrc[1] != crc1) {
								// temp logging
			                	byte getcrc[] = response.getCRC16();
			                	System.out.println("      wrong crc. discarding frame.");
			                	System.out.println("      received crc: "+String.format("0x%02X%02X",crc0,crc1)+" != calculcate crc: "+String.format("0x%02X%02X",getcrc[0],getcrc[1]));
			                	System.out.print("      buffer:");
			                	for(int xi=0; xi<pos; xi++){
			                		System.out.print(" "+String.format("0x%02X",frame[xi]));
			                	}
			                	System.out.println();
			                	
			                	System.out.println("      parser state:"
			                            + " cmd=0x" + String.format("%02X", cmd)
			                            + " length=" + length
			                            + " dataLength=" + dataLength
			                            + " headerLen=" + frameHeaderLength
			                            + " frameLen=" + frameLength
			                            + " pos=" + pos
			                            + " oidIdx=" + oidIdx);

			                    // optional: OID debug (falls im Buffer vorhanden)
			                    if (pos >= oidIdx + 4) {
			                        int oid =
			                                ((frame[oidIdx] & 0xFF) << 24) |
			                                ((frame[oidIdx + 1] & 0xFF) << 16) |
			                                ((frame[oidIdx + 2] & 0xFF) << 8) |
			                                (frame[oidIdx + 3] & 0xFF);

			                        System.out.println("      parsed oid (best effort): 0x"
			                                + Integer.toHexString(oid));
			                    }
			                	
								// discard and resync
								synced = false;
								pos = 0;
								frameHeaderLength = 1 + 1 + 1;
								frameLength = -1;
								length = 0;
								dataLength = 0;
								addressIdx = 0;
								oidIdx = 0;
								escaping = false;
								cmd = 0;
								break;
							}

							return response;
						}

						if (frameLength > 0 && pos > frameLength) {
							throw new IOException("Frame consumed more than expected.");
						}
					}
				}
			}
		} catch (SocketTimeoutException ex) {
			throw ex;
		} catch (IOException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IOException("I/O exception - failed to read.", ex);
		}
	}
}
