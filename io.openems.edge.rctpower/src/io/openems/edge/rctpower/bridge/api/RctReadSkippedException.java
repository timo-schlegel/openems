package io.openems.edge.rctpower.bridge.api;

import io.openems.common.exceptions.OpenemsException;

public class RctReadSkippedException extends OpenemsException {

	private static final long serialVersionUID = 1L;

	public RctReadSkippedException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * @return true if this exception represents a skip that was escalated to hard-fail
	 */
	public boolean isEscalated() {
		return false;
	}
}