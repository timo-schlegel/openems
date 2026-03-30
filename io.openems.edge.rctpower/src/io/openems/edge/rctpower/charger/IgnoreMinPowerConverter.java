package io.openems.edge.rctpower.charger;

import io.openems.edge.rctpower.bridge.api.ElementToChannelConverter;

/**
 * Even if there is no real power from PV, the DC Actual Power Channel
 * could remain on minimum power values. These values are ignored.
 *
 * <p>
 * The class optionally creates a {@link ElementToChannelConverterChain}, for
 * use-cases when additionally to the above 'IgnoreMinPower' logic a scale-factor is
 * required.
 */
public class IgnoreMinPowerConverter extends ElementToChannelConverter {

	/**
	 * Generates an ElementToChannelConverter for the use case covered by
	 * {@link IgnoreMinPowerConverter}.
	 *
	 * @param parent    the parent component
	 * @param converter an additional {@link ElementToChannelConverter}
	 * @return the {@link ElementToChannelConverter}
	 */
	public static ElementToChannelConverter from(RctPowerCharger parent,
			ElementToChannelConverter converter) {
		if (converter == DIRECT_1_TO_1) {
			return new IgnoreMinPowerConverter(parent);
		}
		return ElementToChannelConverter.chain(new IgnoreMinPowerConverter(parent), converter);
	}

	private IgnoreMinPowerConverter(RctPowerCharger parent) {
		super(value -> {
			// Is value null?
			if (value == null) {
				return null;
			}
			// ignore minimum values below 50 W
			if (value instanceof Float f && Math.abs(f) < 50) {
				return 0;
			}
			return value;
		});
	}

}