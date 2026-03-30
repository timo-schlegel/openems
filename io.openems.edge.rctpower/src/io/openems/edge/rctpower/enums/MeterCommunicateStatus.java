package io.openems.edge.rctpower.enums;

import io.openems.common.types.OptionsEnum;

public enum MeterCommunicateStatus implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	NO_METER(0, "NO_METER"), //
	OK(1, "OK"); //

	private final int value;
	private final String option;

	private MeterCommunicateStatus(int value, String option) {
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