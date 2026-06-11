package io.openems.edge.rctpower.bridge.api;

/**
 * Indicates that a read skip was escalated after too many consecutive skipped cycles.
 */
public class RctReadSkippedEscalatedException extends RctReadSkippedException {

	private static final long serialVersionUID = 1L;

	public RctReadSkippedEscalatedException(String message, Throwable cause) {
		super(message, cause);
	}

	@Override
	public boolean isEscalated() {
		return true;
	}
}