package io.openems.edge.rctpower.enums;

import io.openems.common.types.OptionsEnum;

public enum InverterStatus implements OptionsEnum {
	
	UNDEFINED(-1, "Undefined"), //
	SOC(0x00, "Standby"), //
	INITIALIZATION(0x01, "Initialization"), //
	STANDBY(0x02, "Standby"), //
	EFFICIENCY(0x03, "Efficiency"), //
	INSULATION_CHECK(0x04, "Insulation check"), //
	ISLAND_CHECK(0x05, "Island check"), //
	POWER_CHECK(0x06, "Power check"), //
	SYMMETRY(0x07, "Symmetry"), //
	RELAIS_TEST(0x08, "Relais test"), //
	GRID_PASSIV(0x09, "Grid passive"), //
	PREPARE_BAT_PASSIVE(0x0A, "Prepare Bat Passive"), //
	BAT_PASSIV(0x0B, "Battery Passive"), //
	HW_CHECK(0x0C, "H/W check"), //
	FEED_IN(0x0D, "Feed in"); //

	private final int value;
	private final String name;

	private InverterStatus(int value, String name) {
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

