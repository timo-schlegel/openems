package io.openems.edge.rctpower.bridge.api.task;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.rctpower.bridge.api.AbstractRctPowerBridge;
import io.openems.edge.rctpower.bridge.api.RctRequest;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.element.RctElement;
import io.openems.edge.common.taskmanager.Priority;

public abstract class AbstractWriteTask<//
		REQUEST extends RctRequest, //
		RESPONSE extends RctResponse> //
		extends AbstractTask<REQUEST, RESPONSE> implements WriteTask {

	public AbstractWriteTask(String name, Consumer<ExecuteState> onExecute, Class<RESPONSE> responseClazz,
			int oid, RctElement... elements) {
		super(name, onExecute, responseClazz, oid, elements);
	}

	/**
	 * Priority for WriteTasks is by default always HIGH.
	 *
	 * @return {@link Priority#HIGH}
	 */
	@Override
	public Priority getPriority() {
		return Priority.HIGH;
	}

	@Override
	protected final String payloadToString(RESPONSE response) {
		return "";
	}

	public abstract static class Single<//
			REQUEST extends RctRequest, //
			RESPONSE extends RctResponse, //
			ELEMENT extends RctElement> //
			extends AbstractWriteTask<REQUEST, RESPONSE> {

		protected final ELEMENT element;

		private final Logger log = LoggerFactory.getLogger(Single.class);

		public Single(String name, Consumer<ExecuteState> onExecute, Class<RESPONSE> responseClazz, int oid,
				ELEMENT element) {
			super(name, onExecute, responseClazz, oid, element);
			this.element = element;
		}

		@Override
		public final ExecuteState execute(AbstractRctPowerBridge bridge) {
			var result = this._execute(bridge);
			this.onExecute.accept(result);
			return result;
		}

		private ExecuteState _execute(AbstractRctPowerBridge bridge) {
			final REQUEST request;
			try {
				request = this.createRctRequest();
			} catch (OpenemsException e) {
				logError(this.log, e, "Creating Rct Request failed.");
				return new ExecuteState.Error(e);
			}

			if (request == null) {
				return ExecuteState.NO_OP;
			}

			try {
				this.executeRequest(bridge, request);
				return ExecuteState.OK;

			} catch (Exception e) {
				// On error a log message has already been logged
				return new ExecuteState.Error(e);
			}
		}

		/**
		 * Factory for a {@link RctRequest}.
		 * 
		 * @return a new {@link RctRequest}
		 * @throws OpenemsException on error
		 */
		protected abstract REQUEST createRctRequest() throws OpenemsException;
	}
}