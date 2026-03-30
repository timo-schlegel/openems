package io.openems.edge.rctpower.ess;

import io.openems.edge.rctpower.bridge.api.ElementToChannelConverter;

public class ElementToChannelFloatConverter extends ElementToChannelConverter {

	/**
	 * Generates an ElementToChannelConverter for the use case covered by
	 * {@link ElementToChannelFloatConverter}.
	 *
	 * @param parent    the parent component
	 * @param converter an additional {@link ElementToChannelConverter}
	 * @return the {@link ElementToChannelConverter}
	 */			
	public ElementToChannelFloatConverter() {
		super(value -> {
			// Is value null?
			if (value == null) {
				return null;
			}
			// Convert with Math.round(), e.g. 0.9666955 to 97
			return Math.round((Float)value*100);
		});
	}

}
