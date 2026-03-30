package io.openems.edge.rctpower.ess;

import static io.openems.common.utils.IntUtils.maxInt;
import static java.lang.Math.round;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.EnumReadChannel;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.rctpower.enums.AcCharge;
import io.openems.edge.rctpower.enums.BatteryStatus;
import io.openems.edge.rctpower.enums.ControlMode;
import io.openems.edge.rctpower.enums.MeterCommunicateStatus;
import io.openems.edge.rctpower.enums.SocStrategy;

public class ApplyPowerHandler {

	static final float DISCHARGE_EFFICIENCY_FACTOR = 0.95F; // Keep >=0.95 to stay within the min/max limits of AllowedChargeDischargeHandler
	static final int MINIMUM_CHANGE_FOR_WRITE = 10; // Only write if the deviation is greater than 10 W
	
	/**
	 * Apply the desired Active-Power Set-Point by setting the appropriate
	 * SOC_STRATEGY and BATTERY_POWER_EXTERN settings.
	 *
	 * @param rctPower        the RctPower ESS
	 * @param setActivePower  the Active-Power Set-Point
	 * @param controlMode     the {@link ControlMode} to handle the different
	 *                        {@link SocStrategy} for the RctPower battery inverter
	 * @param gridActivePower the grid active power
	 * @param essActivePower  the ESS active power
	 * @param maxAcImport     the max AC import power
	 * @param maxAcExport     the max AC export power
	 * @param isPidEnabled    if PID Filter is enabled
	 * @throws OpenemsNamedException on error
	 */
	public synchronized void apply(RctPowerEss rctPower, int setActivePower, ControlMode controlMode,
			Value<Integer> gridActivePower, Value<Integer> essActivePower, boolean isPidEnabled) throws OpenemsNamedException {
		EnumReadChannel bmsBatteryStatus = rctPower.channel(RctPowerEss.ChannelId.BMS_BATTERY_STATUS);
		
		// Update Warn Channels
		this.checkControlModeRequiresACCharge(rctPower, controlMode);
		this.checkControlModeWithActivePid(rctPower, controlMode, isPidEnabled);
		this.checkControlModeRequiresSmartMeter(rctPower, controlMode);

		// get pv production
		int pvProduction = maxInt(0, rctPower.getPvProduction());

		final ApplyPowerHandler.Result apply;
		if (bmsBatteryStatus.value().asEnum() == BatteryStatus.CALIBRATING_CHARGE
				|| bmsBatteryStatus.value().asEnum() == BatteryStatus.CALIBRATING_DISCHARGE) {
			// switch to internal mode during calibration  
			apply = new ApplyPowerHandler.Result(SocStrategy.CONSTANT, 0);
		} else if (gridActivePower.isDefined() && essActivePower.isDefined()) {
			apply = calculate(rctPower, setActivePower, pvProduction, controlMode, gridActivePower.get(), essActivePower.get());
		} else if(controlMode == ControlMode.CONSTANT) {
			// If any Channel Value is not available and CONSTANT mode is set: fall back to CONSTANT mode
			apply = new ApplyPowerHandler.Result(SocStrategy.CONSTANT, 0);			
		} else {
			// If any Channel Value is not available: fall back to INTERNAL mode
			apply = new ApplyPowerHandler.Result(SocStrategy.INTERNAL, 0);
		}

		// Get Read and Write Channels
		EnumReadChannel remoteControlSocStrategyReadChannel = rctPower.channel(RctPowerEss.ChannelId.SOC_STRATEGY);
		EnumWriteChannel remoteControlSocStrategyWriteChannel = rctPower.channel(RctPowerEss.ChannelId.SOC_STRATEGY);
		IntegerReadChannel remoteControlBatteryPowerReadChannel = rctPower.channel(RctPowerEss.ChannelId.BMS_BATTERY_POWER_EXTERN);
		IntegerWriteChannel remoteControlBatteryPowerWriteChannel = rctPower.channel(RctPowerEss.ChannelId.BMS_BATTERY_POWER_EXTERN);
		
		// Get active values
		var remoteControlSocStrategy = remoteControlSocStrategyReadChannel.value().asEnum();
		var remoteControlBatteryPower = remoteControlBatteryPowerReadChannel.value().orElse(0);
		
		// Write to SocStrategy Channel
		if(remoteControlSocStrategy != SocStrategy.UNDEFINED && remoteControlSocStrategy != apply.socStrategy)
			remoteControlSocStrategyWriteChannel.setNextWriteValue(apply.socStrategy);
			
		// Write to BatteryPowerExtern Channel (if the deviation is greater than MINIMUM_CHANGE_FOR_WRITE)
		if(Math.abs(remoteControlBatteryPower-apply.batteryPowerExtern)>MINIMUM_CHANGE_FOR_WRITE
				||(apply.batteryPowerExtern == 0 && remoteControlBatteryPower != 0)) 
			remoteControlBatteryPowerWriteChannel.setNextWriteValue(apply.batteryPowerExtern);				
	}

	private static record Result(SocStrategy socStrategy, int batteryPowerExtern) {
	}

	private static ApplyPowerHandler.Result calculate(RctPowerEss rctPower, int activePowerSetPoint, int pvProduction,
			ControlMode controlMode, int gridActivePower, int essActivePower)
			throws OpenemsNamedException {
		return switch (controlMode) {
		case INTERNAL //
			-> handleInternalMode();
		case CONSTANT //
			-> handleConstantMode();
		case SMART //
			-> handleSmartMode(rctPower, activePowerSetPoint, pvProduction, gridActivePower, essActivePower);
		case REMOTE //
			-> handleRemoteMode(rctPower, activePowerSetPoint, pvProduction);
		};
	}

	private static Result handleInternalMode() {
		return new Result(SocStrategy.INTERNAL, 0);
	}
	
	private static Result handleConstantMode() {
		return new Result(SocStrategy.CONSTANT, 0);
	}	

	private static Result handleSmartMode(RctPowerEss rctPower, int activePowerSetPoint, int pvProduction,
			int gridActivePower, int essActivePower) throws OpenemsNamedException {

		// Is Surplus-Feed-In active?
		final var surplusPower = rctPower.getSurplusPower();
		var diffSurplus = Integer.MAX_VALUE;
		if (surplusPower != null && surplusPower > 0 && activePowerSetPoint != 0) {
			diffSurplus = activePowerSetPoint - surplusPower;
		}

		// Is Balancing to zero active?
		var diffBalancing = activePowerSetPoint - (gridActivePower + essActivePower);

		if ((diffBalancing > -1 && diffBalancing < 1 || diffSurplus > -1 && diffSurplus < 1) && activePowerSetPoint != 0) {
			// avoid rounding errors
			return handleConstantMode();
		}

		return handleRemoteMode(rctPower, activePowerSetPoint, pvProduction);
	}

	private static Result handleRemoteMode(RctPowerEss rctPower, int activePowerSetPoint, int pvProduction) {
		IntegerReadChannel minSocChannel = rctPower.channel(RctPowerEss.ChannelId.SOC_MIN);
		IntegerReadChannel maxSocChannel = rctPower.channel(RctPowerEss.ChannelId.SOC_MAX);
		Integer maxChargePower = rctPower.getBmsChargeMaxCurrent().orElse(0)*rctPower.getBmsVoltage().orElse(0);
		Integer maxDischargePower = rctPower.getBmsDischargeMaxCurrent().orElse(0)*rctPower.getBmsVoltage().orElse(0);
		
		// temp: log
		final Logger log = LoggerFactory.getLogger(ApplyPowerHandler.class);
				
		// TODO PV curtail: (surplus power == setpoint && battery soc == 100% => PV
		// curtail)
		if (activePowerSetPoint < 0) {
			var result = activePowerSetPoint - pvProduction;
			if(rctPower.getSoc().orElse(100)>=maxSocChannel.value().orElse(0)) {
				if(result != 0) log.info("[Limit] RctPower limited to 0 (battery full)");
				// battery full, limit charge power to zero
				result = 0;
			}			
			else if(result<(maxChargePower*(-1))) {
				// limit to max charge power
				System.out.println("************ Limit to max charge power "+maxChargePower*(-1)+" (was "+result+")");
				result = maxChargePower*(-1);
				log.info("[Limit] RctPower limited to max charge power");
			}
			return new Result(SocStrategy.EXTERNAL, result);
		}
		if (pvProduction >= activePowerSetPoint) {
			// Set-Point is positive && less than PV-Production -> feed PV partly to grid +
			// charge battery
			// On Surplus Feed-In PV == Set-Point => CHARGE_BAT 0
			//var result = round((activePowerSetPoint - pvProduction)*DISCHARGE_EFFICIENCY_FACTOR);
			//if(activePowerSetPoint==0) result = (activePowerSetPoint - pvProduction); // kein DISCHARGE_EFFICIENCY_FACTOR bei PowerSetPoint 0
			//var result = round((activePowerSetPoint - pvProduction)+(activePowerSetPoint*(1-DISCHARGE_EFFICIENCY_FACTOR))); // Decrease battery charge by DISCHARGE_EFFICIENCY_FACTOR to Power which has to be DC-AC-Convertered
			var result = activePowerSetPoint-pvProduction;
			if(result<0) {
				var dischargeEfficencyAbsolute = round(activePowerSetPoint*(1-DISCHARGE_EFFICIENCY_FACTOR)); // Decrease battery charge by DISCHARGE_EFFICIENCY_FACTOR to Power which has to be DC-AC-Convertered
				if(result+dischargeEfficencyAbsolute<0) result = result+dischargeEfficencyAbsolute;
			}
			//System.out.println("charge "+(result)+" (requsted "+(activePowerSetPoint - pvProduction)+") ");
			if(rctPower.getSoc().orElse(100)>=maxSocChannel.value().orElse(0)) {
				if(result!=0) log.info("[Limit] RctPower limited to 0 (battery full)");
				// battery full, limit charge power to zero
				result = 0;
			}			
			else if(result<(maxChargePower*(-1))) {
				// limit to max charge power
				System.out.println("************ Limit to max charge power "+maxChargePower*(-1)+" (was "+result+")");
				result = maxChargePower*(-1);
				log.info("[Limit] RctPower limited to max charge power");
			}
			return new Result(SocStrategy.EXTERNAL, result);
		} else {
			// Set-Point is positive && bigger than PV-Production -> feed all PV to grid +
			// discharge battery
			//var result = round((activePowerSetPoint - pvProduction)/DISCHARGE_EFFICIENCY_FACTOR);
			//var result = round((activePowerSetPoint - pvProduction)+(activePowerSetPoint*(1-DISCHARGE_EFFICIENCY_FACTOR))); // Increase battery discharge by DISCHARGE_EFFICIENCY_FACTOR to Power which has to be DC-AC-Convertered
			var result = activePowerSetPoint-pvProduction;
			result = result+round(activePowerSetPoint*(1-DISCHARGE_EFFICIENCY_FACTOR)); // Increase battery discharge by DISCHARGE_EFFICIENCY_FACTOR to Power which has to be DC-AC-Convertered
			if(rctPower.getSoc().orElse(0)<=minSocChannel.value().orElse(100)) {
				if(result!=0) log.info("[Limit] RctPower limited to 0 (battery empty)");
				// battery empty (=SOC equals or less than soc_min), limit charge power to zero
				result = 0;
			}			
			else if(result>maxDischargePower) {
				// limit to max discharge power
				System.out.println("************ Limit to max discharge power "+maxDischargePower+" (was "+result+")");
				result = maxDischargePower;
				log.info("[Limit] RctPower limited to max discharge power");
			}
			return new Result(SocStrategy.EXTERNAL, result);
		}
	}

	/**
	 * Check if {@link AcCharge} is set to allowed.
	 * If false warning channel AC_CHARGE_NOT_ENABLED is set to true,
	 * otherwise to false.
	 *
	 * @param rctPower     the RctPower ESS
	 * @param controlMode  the {@link ControlMode} to check control mode
	 */
	private void checkControlModeRequiresACCharge(RctPowerEss rctPower, ControlMode controlMode) {
		EnumReadChannel bmsAcChargeChannel = rctPower.channel(RctPowerEss.ChannelId.BMS_ALLOW_AC_CHARGE);
		AcCharge bmsAllowAcCharge = bmsAcChargeChannel.value().asEnum();
		
		var enableWarning = switch (bmsAllowAcCharge) {
		case UNDEFINED -> //
			// We don't know the AC Charge Policy. Not ready yet (on startup)
			false;

		case ALLOWED ->
			// AC Charge is set to allowed.
			false;

		case NOT_ALLOWED //
			-> switch (controlMode) {
			case INTERNAL, CONSTANT ->
				// INTERNAL and CONSTANT mode are ok with any AC Charge setting
				false;
			case REMOTE, SMART ->
				// REMOTE and SMART mode requires AC Charge set to allowed
				true;
			};
		};		
		
		rctPower.channel(RctPowerEss.ChannelId.AC_CHARGE_NOT_ENABLED).setNextValue(enableWarning);
	}	

	/**
	 * Check current {@link ControlMode} is set to SMART and PID filter is enabled.
	 * If true warning channel SMART_MODE_NOT_WORKING_WITH_PID_FILTER set to true,
	 * otherwise to false.
	 *
	 * @param rctPower     the RctPower ESS
	 * @param controlMode  the {@link ControlMode} to check SMART mode
	 * @param isPidEnabled if PID filter is enabled
	 */
	private void checkControlModeWithActivePid(RctPowerEss rctPower, ControlMode controlMode, boolean isPidEnabled) {
		var enableWarning = false;
		if (controlMode.equals(ControlMode.SMART) && isPidEnabled) {
			enableWarning = true;
		}

		rctPower.channel(RctPowerEss.ChannelId.SMART_MODE_NOT_WORKING_WITH_PID_FILTER).setNextValue(enableWarning);
	}

	/**
	 * Check if configured {@link ControlMode} is possible - depending on if a
	 * RctPower Smart Meter is connected or not.
	 *
	 * @param rctPower    the RctPower ESS
	 * @param controlMode the {@link ControlMode} to check control mode
	 */
	private void checkControlModeRequiresSmartMeter(RctPowerEss rctPower, ControlMode controlMode) {
		EnumReadChannel meterCommunicateStatusChannel = rctPower.channel(RctPowerEss.ChannelId.METER_COMMUNICATE_STATUS);
		MeterCommunicateStatus meterCommunicateStatus = meterCommunicateStatusChannel.value().asEnum();

		var enableWarning = switch (meterCommunicateStatus) {
		case UNDEFINED -> //
			// We don't know if RctPower Smart Meter is connected. Not ready yet (on startup)
			false;

		case OK ->
			// RctPower Smart Meter is connected.
			false;

		case NO_METER //
			-> switch (controlMode) {
			case REMOTE ->
				// REMOTE mode is ok without RctPower Smart Meter
				false;
			case INTERNAL, CONSTANT, SMART ->
				// INTERNAL, CONSTANT and SMART mode require a RctPower Smart Meter
				true;
			};
		};

		rctPower.channel(RctPowerEss.ChannelId.NO_SMART_METER_DETECTED).setNextValue(enableWarning);
	}	

}