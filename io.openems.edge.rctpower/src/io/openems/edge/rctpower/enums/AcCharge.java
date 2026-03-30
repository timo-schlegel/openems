package io.openems.edge.rctpower.enums;

import io.openems.common.types.OptionsEnum;

public enum AcCharge implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	ALLOWED(1, "Allowed"), //
	NOT_ALLOWED(0, "Not Allowed"); //

	private final int value;
	private final String option;

	private AcCharge(int value, String option) {
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
