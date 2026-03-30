package io.openems.edge.rctpower.enums;

import io.openems.common.types.OptionsEnum;

public enum SocStrategy implements OptionsEnum {
	
	UNDEFINED(-1, "Undefined"), //
	
	/**
	 * Scenario: to be described
	 *
	 * <p>
	 * to be described
	 */
	SOC(0x00, "SOC"), //
	
	/**
	 * Scenario: Control the battery to charge PV excess only.
	 * 
	 * <p>
	 * Internal battery control without Forcast-based Charging strategy
	 */
	CONSTANT(1, "Constant"), //

	/**
	 * Scenario: External battery control.
	 *
	 * <p>
	 * External battery control
	 */
	EXTERNAL(0x02, "External"), //
	
	/**
	 * Scenario: to be described
	 *
	 * <p>
	 * to be described
	 */
	MIDDLE_BATTERY_VOLTAGE(0x03, "Middle battery voltage"), //
	
	/**
	 * Scenario: Control the battery to charge PV excess only (with optimized charge strategy).
	 *
	 * <p>
	 * Internal battery control with Forcast-based Charging strategy
	 */
	INTERNAL(0x04, "Internal"), //	
	
	/**
	 * Scenario: to be described
	 *
	 * <p>
	 * to be described
	 */
	SCHEDULE(0x00, "Schedule"); //	

	private final int value;
	private final String name;

	private SocStrategy(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}
}	

