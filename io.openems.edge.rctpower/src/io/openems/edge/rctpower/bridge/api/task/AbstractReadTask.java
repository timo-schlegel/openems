package io.openems.edge.rctpower.bridge.api.task;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.rctpower.bridge.api.AbstractRctPowerBridge;
import io.openems.edge.rctpower.bridge.api.RctRequest;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.element.AbstractRctElement;
import io.openems.edge.rctpower.bridge.api.element.RctElement;
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

	private final Logger log = LoggerFactory.getLogger(AbstractReadTask.class);
	private final Priority priority;
	private final Class<?> elementClazz;

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
			// On error a log message has already been logged

			try {
				var result = this.parseResponse(response);
				validateResponse(response, this.length);

				// NOTE: onExecute has to be called before filling elements; but OK could be
				// wrong if fillElements throws an exception.
				this.onExecute.accept(ExecuteState.OK);				
				this.fillElements(result);
				
				return ExecuteState.OK;

			} catch (OpenemsException e1) {
				logError(this.log, e1, "Parsing Response failed.");
				throw e1;
			}

		} catch (Exception e) {
			var executeState = new ExecuteState.Error(e);
			this.onExecute.accept(executeState);

			// Invalidate Elements
			Stream.of(this.elements).forEach(el -> el.invalidate(bridge));
			return executeState;
		}
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
	private void fillElements(T[] response) throws OpenemsException {
		var errors = new ArrayList<String>();

		this.fillElements(FillElementsPriority.HIGH, errors, response);
		this.fillElements(FillElementsPriority.DEFAULT, errors, response);

		if (!errors.isEmpty()) {			
			throw new OpenemsException(String.join(", ", errors));
		}
	}

	@SuppressWarnings("unchecked")
	private void fillElements(FillElementsPriority priority, List<String> errors, T[] response) {
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
}