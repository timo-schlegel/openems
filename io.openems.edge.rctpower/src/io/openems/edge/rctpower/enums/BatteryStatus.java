package io.openems.edge.rctpower.enums;

import io.openems.common.types.OptionsEnum;

public enum BatteryStatus implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	NORMAL(0, "charge/discharge (normal operation)"), //
	IDLE(1, "idle (no CAN-connection inverter -> battery)"), //
	CONNECTING(3, "connecting (inverter -> battery)"), //
	SYNCHRONIZING(5, "synchronizing (inverter -> battery)"), //
	CALIBRATING_CHARGE(8, "calibrating - charging phase (0% --> 100%)"), //
	CALIBRATING_DISCHARGE(1024, "calibrating - discharge phase (xx% --> 0%)"), //
	BALANCING(2048, "balancing"); //

	private final int value;
	private final String option;

	private BatteryStatus(int value, String option) {
		this.value = value;
		this.option = option;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.option;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}
}