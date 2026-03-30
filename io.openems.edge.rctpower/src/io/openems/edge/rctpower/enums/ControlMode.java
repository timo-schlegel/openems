package io.openems.edge.rctpower.enums;

public enum ControlMode {

	/**
	 * Uses the internal 'INTERNAL' mode of the RctPower inverter. This mode control
	 * the battery to charge PV excess only (with Forecast-based Charging strategy).
	 * Allows no remote control of Set-Points. Requires a RctPower Smart Meter at the
	 * grid junction point.
	 */
	INTERNAL,
	/**
	 * Uses the internal 'CONSTANT' mode of the RctPower inverter. This mode control
	 * the battery to charge PV excess only (without Forcast-based Charging strategy).
	 * Allows no remote control of Set-Points. Requires a RctPower Smart Meter at the
	 * grid junction point.
	 */
	CONSTANT,	
	/**
	 * Uses the internal 'CONSTANT' mode of the RctPower inverter but smartly switches to
	 * other modes if required. Requires a RctPower Smart Meter at the grid junction
	 * point.
	 */
	SMART,
	/**
	 * Full control of the RctPower inverter by OpenEMS. Slower than the internal
	 * modes, but does not require a RctPower Smart Meter at the grid junction point.
	 */
	REMOTE;

}