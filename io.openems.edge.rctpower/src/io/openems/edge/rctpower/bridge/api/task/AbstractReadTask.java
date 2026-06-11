package io.openems.edge.rctpower.bridge.api.task;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.rctpower.bridge.api.AbstractRctPowerBridge;
import io.openems.edge.rctpower.bridge.api.RctReadSkippedEscalatedException;
import io.openems.edge.rctpower.bridge.api.RctReadSkippedException;
import io.openems.edge.rctpower.bridge.api.RctRequest;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.element.AbstractRctElement;
import io.openems.edge.rctpower.bridge.api.element.RctElement;
import io.openems.edge.rctpower.bridge.api.worker.RctReadWorker.ResponseEntry;
import io.openems.edge.rctpower.bridge.api.worker.RctReadWorker.ResponseSource;
import io.openems.edge.rctpower.bridge.api.element.AbstractRctElement.FillElementsPriority;

/**
 * An abstract Modbus 'AbstractTask' is holding references to one or more Modbus
 * {@link ModbusElement}s which have register addresses in the same range.
 */
@SuppressWarnings("rawtypes")
public abstract class AbstractReadTask<//
		REQUEST extends RctRequest, //
		RESPONSE extends RctResponse, //
		ELEMENT extends AbstractRctElement, //
		T> //
		extends AbstractTask<REQUEST, RESPONSE> implements ReadTask {

	private static final int MAX_SKIP_CYCLES = 5; // allowed consecutive cycles with a skipped read 

	private final Logger log = LoggerFactory.getLogger(AbstractReadTask.class);
	private final Priority priority;
	private final Class<?> elementClazz;

	private int consecutiveSkips = 0;
	private int suppressedHardFailEscalations = 0;
	private Integer attemptTimeoutMs = null;
	
	public AbstractReadTask(String name, Consumer<ExecuteState> onExecute, Class<RESPONSE> responseClazz,
					Class<ELEMENT> elementClazz, int oid, Priority priority, RctElement... elements) {
		super(name, onExecute, responseClazz, oid, elements);
		this.elementClazz = elementClazz;
		this.priority = priority;
	}

	@Override
	public ExecuteState execute(AbstractRctPowerBridge bridge) {
		try {
			var response = this.executeRequest(bridge, this.createRctRequest());
			var result = this.processResponse(bridge, response);

			// reset skip counter on successful read 
			this.resetConsecutiveSkips();
			this.resetSuppressedHardFailEscalations();

			return result;

		} catch (RctReadSkippedException e) {
			return this.onReadSkipped(bridge, e);

		} catch (Exception e) {
			// Temp
			this.log.info("TEMP direct hard-fail: "
			        + "task=" + this
			        + " attemptTimeoutMs=" + this.getAttemptTimeoutMs()
			        + " message=" + e.getMessage());
			
			return this.onError(bridge, e);
		}
	}

	public final ExecuteState processResponse(AbstractRctPowerBridge bridge, RESPONSE response)
			throws OpenemsException {
		try {
			var result = this.parseResponse(response);
			validateResponse(response, this.length);

			// NOTE: onExecute has to be called before filling elements; but OK could be
			// wrong if fillElements throws an exception.
			this.onExecute.accept(ExecuteState.OK);
			this.fillElements(result);

			return ExecuteState.OK;
		} catch (OpenemsException e) {
			logError(this.log, e, "Parsing Response failed.");
			throw e;
		}
	}

	protected final ExecuteState onReadSkipped(AbstractRctPowerBridge bridge, Exception e) {
		var skipCount = this.incrementConsecutiveSkips();

		if (skipCount > MAX_SKIP_CYCLES) {
			// MAX_SKIP_CYCLES reached -> Return ExecuteRead failed
			var executeState = new ExecuteState.Error(
					new RctReadSkippedEscalatedException(
							"Read failed again after previous skipped cycle: "
									+ "no matching response within timeout for oid [" + this.oid + "]",
							e));

			this.onExecute.accept(executeState);

			// Invalidate Elements
			//this.invalidateElements(bridge); -> will be executed in RctWorker

			// Temp
			this.log.info("TEMP hard-fail escalation after skipped cycles: "
			        + "task=" + this
			        + " consecutiveSkipCycles=" + this.consecutiveSkips
			        + " maxSkipCycles=" + MAX_SKIP_CYCLES
			        + " attemptTimeoutMs=" + this.getAttemptTimeoutMs()
			        + " message=" + e.getMessage());
			
			return executeState;
		}

		// 1st skip will be tolerated -> Return ExecuteRead skipped
		var executeState = new ExecuteState.Skipped(e);
		this.onExecute.accept(executeState);

		// No Invalidate
		return executeState;
	}

	protected final void onReadSuccess() {
		this.resetConsecutiveSkips();
		this.resetSuppressedHardFailEscalations();
	}

	protected final ExecuteState onError(AbstractRctPowerBridge bridge, Exception e) {
		var executeState = new ExecuteState.Error(e);
		this.onExecute.accept(executeState);
		
		// Invalidate Elements
		this.invalidateElements(bridge);
		return executeState;
	}

	@SuppressWarnings("unchecked")
	private void logBatchRead(AbstractRctPowerBridge bridge, RctResponse response, ResponseEntry entry,
	        Exception e, String prefix, long durationNanos) {
		var logVerbosity = this.getLogVerbosity(bridge);

		RESPONSE typedResponse = null;
		if (response != null) {
			typedResponse = (RESPONSE) response;
		}

		String elapsed = "Elapsed [" + TimeUnit.NANOSECONDS.toMillis(durationNanos) + "ms]";
		String responseDetails = "source=missing";

		if (entry != null) {
		    responseDetails = "source=" + entry.source().name().toLowerCase();

		    if (entry.source() == ResponseSource.CACHE) {
		        responseDetails += " cycleAge=" + entry.cycleAge();
		    }
		}

		switch (logVerbosity) {
		case NONE, DEBUG_LOG -> {
			if (e != null) {
				logError(this.log, e, prefix,
						this.toLogMessage(logVerbosity, this.oid, this.length, (REQUEST) null, typedResponse, e),
						responseDetails);
			}
		}
		case READS_AND_WRITES, READS_AND_WRITES_VERBOSE -> {
			if (e == null) {
				logInfo(this.log, prefix,
						this.toLogMessage(logVerbosity, this.oid, this.length, (REQUEST) null, typedResponse, null),
						responseDetails);
			} else {
				logError(this.log, e, prefix,
						this.toLogMessage(logVerbosity, this.oid, this.length, (REQUEST) null, typedResponse, e),
						responseDetails);
			}
		}
		case READS_AND_WRITES_DURATION, READS_AND_WRITES_DURATION_TRACE_EVENTS -> {
			if (e == null) {
				logInfo(this.log, prefix,
						this.toLogMessage(logVerbosity, this.oid, this.length, (REQUEST) null, typedResponse, null),
						elapsed,
						responseDetails);
			} else {
				logError(this.log, e, prefix,
						this.toLogMessage(logVerbosity, this.oid, this.length, (REQUEST) null, typedResponse, e),
						elapsed,
						responseDetails);
			}
		}
		}
	}

	@Override
	public final ExecuteState executeBatchResponse(AbstractRctPowerBridge bridge, RctResponse response, ResponseEntry entry,
			long durationNanos) {
		try {
			var result = this.processResponse(bridge, this.responseClazz.cast(response));
			this.onReadSuccess();

			this.logBatchRead(bridge, response, entry, null, "Batch execute", durationNanos);

			return result;

		} catch (Exception e) {
			this.logBatchRead(bridge, response, entry, e, "Batch execute failed", durationNanos);
			return this.onError(bridge, e);
		}
	}

	@Override
	public final ExecuteState executeBatchSkipped(AbstractRctPowerBridge bridge, Exception e, long durationNanos) {
		var result = this.onReadSkipped(bridge, e);

		if (result instanceof ExecuteState.Error) {
			this.logBatchRead(bridge, null, null, e, "Batch execute failed", durationNanos);
		} else {
			this.logBatchRead(bridge, null, null, null, "Batch execute skipped", durationNanos);
		}

		return result;
	}

	protected final void invalidateElements(AbstractRctPowerBridge bridge) {
		Stream.of(this.elements).forEach(el -> el.invalidate(bridge));
	}

	protected final int getOid() {
		return this.oid;
	}

	/**
	 * Verify length of response array.
	 * 
	 * @param response response array
	 * @param length   expected length
	 * @throws OpenemsException on failed validation
	 */
	private static void validateResponse(RctResponse response, int length) throws OpenemsException {
		if (response.getData().length < length) {
			throw new OpenemsException("Received message is too short. " //
					+ "Expected [" + length + "] " //
					+ "Got [" + response.getData().length + "] bytes.");
		}
	}

	/**
	 * Fills {@link RctElement}s with values from response.
	 * 
	 * @param response the response values
	 * @throws OpenemsException on error
	 */
	protected final void fillElements(T[] response) throws OpenemsException {
		var errors = new ArrayList<String>();

		this.fillElements(FillElementsPriority.HIGH, errors, response);
		this.fillElements(FillElementsPriority.DEFAULT, errors, response);

		if (!errors.isEmpty()) {			
			throw new OpenemsException(String.join(", ", errors));
		}
	}

	@SuppressWarnings("unchecked")
	private void fillElements(FillElementsPriority priority, ArrayList<String> errors, T[] response) {
		var position = 0;
		
		for (var element : this.elements) {			
			// Filter for FillElementsPriority
			@SuppressWarnings("deprecation")
			var thisPriority = ((AbstractRctElement<?, ?, ?>) element)._getFillElementsPriority();
			if (thisPriority == priority) {	
				if (this.elementClazz.isInstance(element))  {					
					try {
						this.handleResponse((ELEMENT) element, position, response);
					} catch (OpenemsException e) {
						errors.add("Unable to fill Rct Element. " //
								+ element.toString() + " Error: " + e.getMessage());
					}
				} else {
					errors.add("Wrong type while filling Rct Element. " //
							+ element.toString() + " " //
							+ "Expected [" + this.elementClazz.getSimpleName() + "] " //
							+ "Got [" + element.getClass().getSimpleName() + "]");
				}
			}
			position = this.calculateNextPosition(element, position);
		}
	}

	@Override
	public Priority getPriority() {
		return this.priority;
	}

	/**
	 * Handle a Response, e.g. set the internal value.
	 * 
	 * @param element  the {@link RctElement}
	 * @param position the current position
	 * @param response the converted {@link RctResponse} values
	 * @throws OpenemsException on error
	 */
	protected abstract void handleResponse(ELEMENT element, int position, T[] response) throws OpenemsException;

	/**
	 * Calculate the position of the next Element.
	 * 
	 * @param position   current position
	 * @param rctElement current Element
	 * @return next position
	 */
	protected abstract int calculateNextPosition(RctElement rctElement, int position);

	/**
	 * Factory for a {@link RctRequest}.
	 * 
	 * @return a new {@link RctRequest}
	 */
	protected abstract REQUEST createRctRequest();

	/**
	 * Parses a {@link RctResponse} to an array of values.
	 * 
	 * @param response the {@link RctResponse}
	 * @return array of results
	 * @throws OpenemsException on error
	 */
	protected abstract T[] parseResponse(RESPONSE response) throws OpenemsException;
	
	@Override
	protected final String payloadToString(REQUEST request) {
		return "";
	}

	protected final void resetConsecutiveSkips() {
		this.consecutiveSkips = 0;
	}

	protected final int incrementConsecutiveSkips() {
		return ++this.consecutiveSkips;
	}

	protected final int getConsecutiveSkips() {
		return this.consecutiveSkips;
	}

	public final int incrementSuppressedHardFailEscalations() {
		return ++this.suppressedHardFailEscalations;
	}

	public final void resetSuppressedHardFailEscalations() {
		this.suppressedHardFailEscalations = 0;
	}

	public final int getSuppressedHardFailEscalations() {
		return this.suppressedHardFailEscalations;
	}

	@Override
	public void setAttemptTimeoutMs(Integer attemptTimeoutMsOverride) {
	    this.attemptTimeoutMs = attemptTimeoutMsOverride;
	}

	@Override
	public Integer getAttemptTimeoutMs() {
	    return this.attemptTimeoutMs;
	}

	@Override
	public void clearAttemptTimeoutMs() {
	    this.attemptTimeoutMs = null;
	}
}