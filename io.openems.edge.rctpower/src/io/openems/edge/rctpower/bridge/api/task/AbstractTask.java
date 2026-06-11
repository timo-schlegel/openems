package io.openems.edge.rctpower.bridge.api.task;

import static io.openems.common.utils.FunctionUtils.doNothing;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.function.ThrowingSupplier;
import io.openems.edge.rctpower.bridge.api.AbstractOpenemsRctComponent;
import io.openems.edge.rctpower.bridge.api.AbstractRctPowerBridge;
import io.openems.edge.rctpower.bridge.api.LogVerbosity;
import io.openems.edge.rctpower.bridge.api.RctReadSkippedException;
import io.openems.edge.rctpower.bridge.api.RctRequest;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.element.RctElement;

/**
 * An abstract Modbus 'AbstractTask' is holding references to one or more Modbus
 * {@link ModbusElement}s which have register addresses in the same range.
 */
public abstract non-sealed class AbstractTask<//
		REQUEST extends RctRequest, //
		RESPONSE extends RctResponse> implements Task {

	protected final String name;
	protected final Consumer<ExecuteState> onExecute;
	protected final Class<RESPONSE> responseClazz;
	protected final int oid;
	protected final int startAddress;
	protected final int length;
	protected final RctElement[] elements;

	private final Logger log = LoggerFactory.getLogger(AbstractTask.class);

	private AbstractOpenemsRctComponent parent = null; // this is always set by ModbusProtocol.addTask()

	public AbstractTask(String name, Consumer<ExecuteState> onExecute, Class<RESPONSE> responseClazz, int startAddress,
			RctElement... elements) {
		this.name = name;
		this.onExecute = onExecute;
		this.responseClazz = responseClazz;
		this.oid = startAddress;
		this.startAddress = startAddress;
		this.elements = elements;
		var nextStartAddress = startAddress;
		var length = 0;
		for (var element : elements) {
			if (element.startAddress != nextStartAddress) {
				throw new IllegalArgumentException("StartAddress for Modbus Element wrong. " //
						+ "Got [" + element.startAddress + "/0x" + Integer.toHexString(element.startAddress) + "] " //
						+ "Expected [" + nextStartAddress + "/0x" + Integer.toHexString(nextStartAddress) + "]");
			}
			nextStartAddress += element.length;
			length += element.length;
			element.setRctTask(this);
		}
		this.length = length;
	}

	// Override for Task.getElements()
	public RctElement[] getElements() {
		return this.elements;
	}

	// Override for Task.getLength()
	public int getLength() {
		return this.length;
	}

	// Override for Task.getStartAddress()
	public int getStartAddress() {
		return this.startAddress;
	}

	public void setParent(AbstractOpenemsRctComponent parent) {
		this.parent = parent;
	}

	public AbstractOpenemsRctComponent getParent() {
		return this.parent;
	}

	/**
	 * Executes the tasks - i.e. sends the query of a ReadTask or writes a
	 * WriteTask.
	 *
	 * @param bridge the Rct-Bridge
	 * @return the {@link ExecuteState}
	 */
	public abstract ExecuteState execute(AbstractRctPowerBridge bridge);

	/**
	 * Actually executes a {@link ModbusRequest} and returns its
	 * {@link ModbusResponse}.
	 * 
	 * <p>
	 * If first request fails, the implementation reconnects the Rct connection
	 * and tries again.
	 * 
	 * <p>
	 * Successful execution is produces a log message if {@link LogVerbosity} !=
	 * 'NONE' was configured. Errors are always logged.
	 * 
	 * @param bridge  the {@link AbstractRctPowerBridge}
	 * @param request the typed {@link RctRequest}
	 * @return the typed {@link RctResponse}
	 * @throws OpenemsException on error
	 */
	protected RESPONSE executeRequest(AbstractRctPowerBridge bridge, REQUEST request) throws Exception {
		var slaveId = this.getParent().getSlaveId();
		var logVerbosity = this.getLogVerbosity(bridge);
		try {
			// First try
			return this.logRequest(TryExecute.FIRST_TRY, bridge, logVerbosity, request,
					() -> this.sendRequest(bridge, slaveId, this.responseClazz, request));

	    } catch (RctReadSkippedException e) {
	        // Soft-fail / timeout-like:
	        // no retry, because we do not want to extend the read cycle
	        throw e;

		} catch (Exception e) {
			// Hard-fail:
			// Second try; with new connection
			bridge.closeRctConnection();
			return this.logRequest(TryExecute.SECOND_TRY, bridge, logVerbosity, request,
					() -> this.sendRequest(bridge, slaveId, this.responseClazz, request));
		}
	}

	private static enum TryExecute {
		FIRST_TRY, SECOND_TRY
	}

	/**
	 * Logs the execution of a {@link ModbusRequest}.
	 * 
	 * @param tryExecute   marker for execute first/second try
	 * @param bridge       the {@link BridgeRctPower}
	 * @param logVerbosity the {@link LogVerbosity}
	 * @param request      the {@link RctRequest}
	 * @param supplier     {@link ThrowingSupplier} that executes the Request and
	 *                     returns a Response
	 * @return typed {@link RctResponse}
	 * @throws Exception on error
	 */
	protected RESPONSE logRequest(TryExecute tryExecute, AbstractRctPowerBridge bridge, LogVerbosity logVerbosity,
			REQUEST request, ThrowingSupplier<RESPONSE, Exception> supplier) throws Exception {
		return switch (logVerbosity) {
		case NONE, DEBUG_LOG -> {
			yield switch (tryExecute) {
			case FIRST_TRY ->
				// On first try: do not log error in low LogVerbosity
				supplier.get();
			case SECOND_TRY -> {
				// On second try: always log error
				try {
					yield supplier.get();
				} catch (RctReadSkippedException e) {
					this.logError(e, "Execute skipped", this.toLogMessage(logVerbosity, request, e));
					throw e;
				} catch (Exception e) {
					this.logError(e, "Execute failed", this.toLogMessage(logVerbosity, request, e));
					throw e;
				}
			}
			};
		}

		case READS_AND_WRITES, READS_AND_WRITES_VERBOSE -> {
			try {
				var response = supplier.get();
				this.logInfo("  Execute", this.toLogMessage(logVerbosity, request, response));
				yield response;

			} catch (RctReadSkippedException e) {
				this.logError(e, "  Execute skipped", this.toLogMessage(logVerbosity, request, e));
				throw e;			
			} catch (Exception e) {
				this.logError(e, "  Execute failed", this.toLogMessage(logVerbosity, request, e));
				throw e;
			}
		}

		case READS_AND_WRITES_DURATION, READS_AND_WRITES_DURATION_TRACE_EVENTS -> {
			var stopwatch = Stopwatch.createStarted();
			try {
				var response = supplier.get();
				stopwatch.stop();
				this.logInfo("  Execute", this.toLogMessage(logVerbosity, request, response),
						"Elapsed [" + stopwatch.elapsed(TimeUnit.MILLISECONDS) + "ms]");
				yield response;

			} catch (RctReadSkippedException e) {
				stopwatch.stop();
				this.logError(e, "  Execute skipped", this.toLogMessage(logVerbosity, request, e),
						"Elapsed [" + stopwatch.elapsed(TimeUnit.MILLISECONDS) + "ms]");
				throw e;
			} catch (Exception e) {
				stopwatch.stop();
				this.logError(e, "  Execute failed", this.toLogMessage(logVerbosity, request, e),
						"Elapsed [" + stopwatch.elapsed(TimeUnit.MILLISECONDS) + "ms]");
				throw e;
			}
		}
		};
	}

	/*
	 * Enable Debug mode for this Element. Activates verbose logging.
	 */
	private boolean isDebug = false;

	/**
	 * Activate Debug-Mode.
	 * 
	 * @return myself
	 */
	public AbstractTask<REQUEST, RESPONSE> debug() {
		this.isDebug = true;
		return this;
	}

	/**
	 * Combines the global and local (via {@link #isDebug} log verbosity.
	 *
	 * @param bridge the parent Bridge
	 * @return the combined LogVerbosity
	 */
	protected LogVerbosity getLogVerbosity(AbstractRctPowerBridge bridge) {
		if (this.isDebug) {
			return LogVerbosity.READS_AND_WRITES_VERBOSE;
		}
		return bridge.getLogVerbosity();
	}

	/**
	 * Deactivate.
	 */
	public void deactivate() {
		for (RctElement element : this.elements) {
			element.deactivate();
		}
	}

	private void logInfo(String... messages) {
		logInfo(this.log, messages);
	}

	protected static void logInfo(Logger log, String... messages) {
		log(log, Logger::info, messages);
	}

	private void logError(Exception e, String... messages) {
		logError(this.log, e, messages);
	}

	protected static void logError(Logger log, Exception e, String... messages) {
		messages = Arrays.copyOf(messages, messages.length + 1);
		messages[messages.length - 1] = e.getClass().getSimpleName() + ": " + e.getMessage();
		log(log, Logger::error, messages);
	}

	private static void log(Logger log, BiConsumer<Logger, String> logger, String... messages) {
		logger.accept(log, String.join(" ", messages));
	}

	protected final String toLogMessage(LogVerbosity logVerbosity, REQUEST request, Exception e) {
		return this.toLogMessage(logVerbosity, request, null, e);
	}

	protected final String toLogMessage(LogVerbosity logVerbosity, REQUEST request, RESPONSE response) {
		return this.toLogMessage(logVerbosity, request, response, null);
	}

	// This method has an @Override in FC16WriteRegistersTask
	protected String toLogMessage(LogVerbosity logVerbosity, REQUEST request, RESPONSE response, Exception e) {
		return this.toLogMessage(logVerbosity, this.startAddress, this.length, request, response, e);
	}

	/**
	 * Generates a log message for this task.
	 * 
	 * <p>
	 * For certain Exceptions we internally increase the LogVerbosity to always show
	 * helpful information
	 * 
	 * @param logVerbosity the {@link LogVerbosity}
	 * @param oid          the object id of the request
	 * @param length       the length of the request payload
	 * @param request      the {@link RctRequest}
	 * @param response     the {@link RctResponse}, possibly null
	 * @param exception    a {@link Exception}, possibly null
	 * @return a log message String
	 */
	protected final String toLogMessage(LogVerbosity logVerbosity, int oid, int length, REQUEST request,
			RESPONSE response, Exception exception) {
		// Handle Exception
		if (exception != null) {
			//if (exception instanceof ModbusSlaveException e && e.isType(Modbus.ILLEGAL_VALUE_EXCEPTION)) {
			//	// In this case it is helpful to get see the detailed request payload
			//	logVerbosity = LogVerbosity.READS_AND_WRITES_VERBOSE;
			//}
		}

		// Build log message
		var b = new StringBuilder() //
				.append(this.name) //
				.append(" [") //
				.append(this.parent.id()) //
				.append(";slaveid=").append(this.parent.getSlaveId()); //
		if (!(this instanceof WriteTask)) { // WriteTasks anyway default to HIGH priority
			b.append(";priority=").append(this.getPriority());
		}
		b //
				.append(";oid=").append("0x").append(Integer.toHexString(oid)) //
				.append(";length=").append(length); //
		switch (logVerbosity) {
		case NONE, DEBUG_LOG, READS_AND_WRITES, READS_AND_WRITES_DURATION, READS_AND_WRITES_DURATION_TRACE_EVENTS //
			-> doNothing();
		case READS_AND_WRITES_VERBOSE -> {
			if (request != null) {
				var hexString = this.payloadToString(request);
				if (!hexString.isBlank()) {
					b.append(";request=").append(hexString);
				}
			}
			if (response != null) {
				var hexString = this.payloadToString(response);
				if (!hexString.isBlank()) {
					b.append(";response=").append(hexString);
				}
			}
		}
		}
		return b //
				.append("]") //
				.toString();
	}

	/**
	 * Converts the actual payload of the REQUEST to a human readable format
	 * suitable for logs; without header data (like Slave-ID, object id,
	 * checksum, etc).
	 * 
	 * @param request the request
	 * @return a string
	 */
	protected abstract String payloadToString(REQUEST request);

	/**
	 * Converts the actual payload of the RESPONSE to a human readable format
	 * suitable for logs; without header data (like Slave-ID, object id,
	 * checksum, etc).
	 * 
	 * @param response the response
	 * @return a string
	 */
	protected abstract String payloadToString(RESPONSE response);

	/**
	 * Sends a {@link RctRequest} and returns the {@link RctResponse}.
	 * 
	 * @param <RESPONSE> the type of the response
	 * @param bridge     the {@link AbstractRctPowerBridge}
	 * @param slaveId    the Rctpower Slave-ID (0 for Master)
	 * @param clazz      the class of the response
	 * @param request    the {@link RctRequest}
	 * @return the {@link RctResponse}
	 * @throws Exception on error
	 */
	private RESPONSE sendRequest(AbstractRctPowerBridge bridge, int slaveId,
			Class<RESPONSE> clazz, RctRequest request) throws Exception {
		/*
		 * !!!! change setUnitID() to setSlaveID() !!! 
		 * 
		 */
		//request.setSlaveID(slaveId);
		var transaction = bridge.getNewRctTransaction(request);
				
	    if (this instanceof ReadTask readTask) {
	        Integer attemptTimeoutMs = readTask.getAttemptTimeoutMs();
	        if (attemptTimeoutMs != null) {
	            transaction.setAttemptTimeoutMs(attemptTimeoutMs);
	        }
	    }

		if(request.getCommand() == 0x02)
		{
			transaction.executeWriteOnly();
			Thread.sleep(AbstractRctPowerBridge.WAIT_AFTER_WRITE);
			return null;
		}
			
		//Thread.sleep(20); // !!! TEMP
		transaction.execute();	
		var response = transaction.getResponse();
		if (clazz.isInstance(response)) {
			return (RESPONSE) clazz.cast(response);
		}

		throw new OpenemsException("Unexpected Rct response. " //
				+ "Expected [" + clazz.getSimpleName() + "] " //
				+ "Got [" + response.getClass().getSimpleName() + "]");
	}
}