package io.openems.edge.rctpower.bridge.api;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.rctpower.bridge.api.worker.RctReadWorker;

public class RctTransaction {

		private static final int DEFAULT_ATTEMPT_TIMEOUT_MS = 150;

		private static final Map<Integer, Long> lastSuccessfulSeqByOid = new ConcurrentHashMap<>();

		private RctTCPConnection rctTCPConnection;
		private RctTCPTransport rctIO;
		private RctFrame rctRequest;
		private final RctReadWorker readWorker;
		private RctResponse rctResponse;

		/**
		 * Total timeout budget for one attempt.
		 */
		private int attemptTimeoutMs = DEFAULT_ATTEMPT_TIMEOUT_MS;

		public RctTransaction(RctTCPConnection con, RctFrame req, RctReadWorker readWorker) {
			this.rctTCPConnection = con;
			this.rctIO = con.getRctTCPTransport(); 
			this.rctRequest = req;
			this.readWorker = readWorker;
		}
		
		public RctFrame getRequest() {
			return rctRequest;
		}

		public RctResponse getResponse() {
			return rctResponse;
		}

		public void setAttemptTimeoutMs(int attemptTimeoutMs) {
			this.attemptTimeoutMs = Math.max(1, attemptTimeoutMs);
		}
		
		private static final boolean DEBUG_IO = false;

		private static void debugIo(String msg) {
			if (DEBUG_IO) {
				System.out.println(msg);
			}
		}
		
		private static String formatOid(RctFrame frame) {
			if (frame == null || frame.getID() == null || frame.getID().length != 4) {
				return "unknown";
			}
			return "0x" + HexFormat.of().withUpperCase().formatHex(frame.getID());
		}

		private static String formatOid(RctResponse response) {
			if (response == null || response.getID() == null || response.getID().length != 4) {
				return "unknown";
			}
			return "0x" + HexFormat.of().withUpperCase().formatHex(response.getID());
		}

		private static String formatCommand(byte command) {
			return String.format("0x%02X", command);
		}

		private static boolean isMatchingResponse(RctFrame request, RctResponse response) {
			if (request == null || response == null) {
				return false;
			}

			final byte[] requestOid = request.getID();
			final byte[] responseOid = response.getID();

			if (requestOid == null || responseOid == null) {
				return false;
			}

			return Arrays.equals(requestOid, responseOid);
		}

		public void execute() throws OpenemsException {
		    if (this.rctRequest == null || this.rctTCPConnection == null) {
		        throw new OpenemsException("Invalid request or connection");
		    }

		    final String requestOid = formatOid(this.rctRequest);

		    ensureConnected();

		    try {
		        synchronized (this.rctIO) {
		            debugIo("[RCT-TX] attempt=1/1"
		                    + " oid=" + requestOid
		                    + " timeout=" + this.attemptTimeoutMs + "ms");

		            int expectedOid = bytesToInt(this.rctRequest.getID());

		            long marker = this.readWorker.mark();

		            synchronized (this.rctIO) {
		            	this.rctIO.writeMessage(this.rctRequest);
		            }

		            var result = this.readWorker.awaitResponses(
		            		java.util.Set.of(expectedOid),
		            		marker,
		            		this.attemptTimeoutMs);

		            var entry = result.responses().get(expectedOid);

		            //if (entry == null) {
		            //	throw new RctReadSkippedException(
		            //			"Missing response for oid [" + Integer.toHexString(expectedOid) + "]",
		            //			new java.net.SocketTimeoutException("Missing response in single read"));
		            //}

		            // TEMP
		            if (entry == null) {
		            	var latest = this.readWorker.getLatestForDebug(expectedOid);

		            	long currentCycle = this.readWorker.getCurrentCycleId();

		            	Long lastSuccessSeq = lastSuccessfulSeqByOid.get(expectedOid);
		            	boolean cacheNewerThanLastSuccess = latest.isPresent()
		            			&& (lastSuccessSeq == null || latest.get().sequence() > lastSuccessSeq);

		            	String latestInfo = "";
		            	if (latest.isPresent()) {
		            		var v = latest.get();
		            		long cycleDiff = currentCycle - v.cycleId();

		            		latestInfo = " latestSeq=" + v.sequence()
		            				+ " latestCycle=" + v.cycleId()
		            				+ " cycleDiff=" + cycleDiff
		            				+ " latestAgeMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - v.receivedAtNanos());
		            	}

		            	System.out.println("TEMP single read missing:"
		            			+ " oid=0x" + Integer.toHexString(expectedOid)
		            			+ " currentCycle=" + currentCycle
		            			+ " marker=" + marker
		            			+ " missing=" + result.missingOids()
		            			+ " latestPresent=" + latest.isPresent()
		            			+ " lastSuccessSeq=" + lastSuccessSeq
		            			+ " cacheNewerThanLastSuccess=" + cacheNewerThanLastSuccess
		            			+ latestInfo);

		            	throw new RctReadSkippedException(
		            			"Missing response for oid [" + Integer.toHexString(expectedOid) + "]",
		            			new java.net.SocketTimeoutException("Missing response in single read"));
		            }

		            var cached = entry.cachedFrame();

		            this.rctResponse = (RctResponse) cached.frame();

		            lastSuccessfulSeqByOid.put(expectedOid, cached.sequence());

		            debugIo("[RCT-RX-OK] attempt=1/1"
		                    + " requestOid=" + requestOid
		                    + " responseOid=" + formatOid(this.rctResponse));
		        }

		    } catch (InterruptedException ex) {
		    	Thread.currentThread().interrupt();
		    	throw new OpenemsException("RCT transaction interrupted", ex);

		    } catch (IOException ex) {
		        final boolean timeoutLike = isTimeoutLike(ex);

		        debugIo("[RCT-RX-FAIL] attempt=1/1"
		                + " oid=" + requestOid
		                + " timeoutLike=" + timeoutLike
		                + " message=" + ex.getMessage());

		        if (timeoutLike) {
		            throw new RctReadSkippedException(
		                    "No matching response within timeout for read request " + requestOid + " on attempt 1/1",
		                    ex);
		        }

		        safelyCloseConnection();
		        throw new OpenemsException(
		                "Connection lost on attempt 1/1: " + ex.getMessage(),
		                ex);
		    }
		}

		public void executeWriteOnly() throws OpenemsException {
		    if (this.rctRequest == null || this.rctTCPConnection == null) {
		        throw new OpenemsException("Invalid request or connection");
		    }

		    ensureConnected();

		    try {
		        synchronized (this.rctIO) {
		            this.rctIO.writeMessage(this.rctRequest);
		            this.rctResponse = null;
		        }

		    } catch (IOException ex) {
		        safelyCloseConnection();
		        throw new OpenemsException("Executing write transaction failed: " + ex.getMessage(), ex);
		    }
		}

		private void ensureConnected() throws OpenemsException {
			if (!this.rctTCPConnection.isConnected()) {
				try {
					this.rctTCPConnection.ensureConnected();
				} catch (Exception ex) {
					throw new OpenemsException("Connection failed.", ex);
				}
			}
		}

		// Obsolete
		private RctResponse readMatchingResponseWithinAttemptBudget(int attempt, int maxAttempts) throws IOException {
			final long timeoutMs = this.attemptTimeoutMs > 0
					? this.attemptTimeoutMs
					: AbstractRctPowerBridge.DEFAULT_TIMEOUT;

			final long deadlineNs = System.nanoTime() + millisToNanos(timeoutMs);
			final String expectedOidText = formatOid(this.rctRequest);

			IOException lastException = null;

			while (true) {
				final long remainingMs = remainingMillis(deadlineNs);
				if (remainingMs <= 0) {
					if (lastException != null) {
						throw new IOException(
								"Timeout reading matching response for oid=" + expectedOidText
										+ " on attempt " + attempt + "/" + maxAttempts,
								lastException);
					}
					throw new IOException(
							"Timeout reading matching response for oid=" + expectedOidText
									+ " on attempt " + attempt + "/" + maxAttempts);
				}

				final RctResponse response;
				try {
					response = this.rctIO.readResponse((int) Math.max(1, remainingMs));
				} catch (IOException ex) {
					lastException = ex;

					/*
					 * Bei echtem Timeout innerhalb dieses remaining window:
					 * Attempt endet, execute() entscheidet über Retry.
					 */
					throw ex;
				}

				if (response == null) {
					continue;
				}

				if (isMatchingResponse(this.rctRequest, response)) {
					debugIo("[RCT-RX-MATCH] requestOid=" + expectedOidText
							+ " responseOid=" + formatOid(response)
							+ " responseCmd=" + formatCommand(response.getCommand())
							+ " remainingBudget=" + remainingMs + "ms");
					return response;
				}

				debugIo("[RCT-RX-SKIP] requestOid=" + expectedOidText
						+ " responseOid=" + formatOid(response)
						+ " responseCmd=" + formatCommand(response.getCommand())
						+ " dataLength=" + (response.getData() != null ? response.getData().length : 0)
						+ " remainingBudget=" + remainingMs + "ms");
			}
		}
		
		private static boolean isTimeoutLike(IOException ex) {
			if (ex instanceof SocketTimeoutException) {
				return true;
			}
			final String msg = ex.getMessage();
			return msg != null && msg.toLowerCase().contains("timeout");
		}

		private void safelyCloseConnection() {
			try {
				if (this.rctTCPConnection.isConnected()) {
					this.rctTCPConnection.close();
				}
			} catch (Exception ignore) {
				// ignore
			}
		}

		private static long millisToNanos(long millis) {
			return millis * 1_000_000L;
		}

		private static long remainingMillis(long deadlineNs) {
			return Math.max(0, (deadlineNs - System.nanoTime()) / 1_000_000L);
		}

		private static int max(int a, int b) {
			return Math.max(a, b);
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
}
