package io.openems.edge.rctpower.ess;

import static io.openems.common.utils.IntUtils.maxInt;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.EnumReadChannel;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.filter.PT1Filter;
import io.openems.edge.rctpower.enums.AcCharge;
import io.openems.edge.rctpower.enums.BatteryStatus;
import io.openems.edge.rctpower.enums.ControlMode;
import io.openems.edge.rctpower.enums.MeterCommunicateStatus;
import io.openems.edge.rctpower.enums.SocStrategy;

public class ApplyPowerHandler {

	static final float DISCHARGE_EFFICIENCY_FACTOR = 0.97F;
	static final int MINIMUM_CHANGE_FOR_WRITE = 10; // Only write if the deviation is greater than 10 W
	static final int MIN_BATTERY_POWER = 50; // [W]
	static final int MAX_CHARGE_INCREASE_PER_SECOND = 3000; // max increase [W] towards more charge per second
	static final int MAX_DISCHARGE_INCREASE_PER_SECOND = 750; // max decrease [W] towards more discharge per second

	private final PT1Filter internalFilter = new PT1Filter(800 /* [ms */);
	
	private Integer lastBatteryPower;

	/**
	 * Apply the desired Active-Power Set-Point by setting the appropriate
	 * SOC_STRATEGY and BATTERY_POWER_EXTERN settings.
	 *
	 * @param rctPower              the RctPower ESS
	 * @param setActivePower        the Active-Power Set-Point
	 * @param controlMode           the {@link ControlMode} to handle the different
	 *                              {@link SocStrategy} for the RctPower battery inverter
	 * @param gridActivePower       the grid active power
	 * @param essActivePower        the ESS active power
	 * @param isGlobalFilterEnabled is global {@link Filter} enabled?
	 * @throws OpenemsNamedException on error
	 */
	public synchronized void apply(RctPowerEss rctPower, int setActivePower, ControlMode controlMode,
			Value<Integer> gridActivePower, Value<Integer> essActivePower, boolean isGlobalFilterEnabled,
			int cycleTime) throws OpenemsNamedException {
		EnumReadChannel bmsBatteryStatus = rctPower.channel(RctPowerEss.ChannelId.BMS_BATTERY_STATUS);
		
		// Update Warn Channels
		this.checkControlModeRequiresACCharge(rctPower, controlMode);
		this.checkControlModeWithActivePid(rctPower, controlMode, isGlobalFilterEnabled);
		this.checkControlModeRequiresSmartMeter(rctPower, controlMode);

		// get pv production
		int pvProduction = maxInt(0, rctPower.getPvProduction());

		ApplyPowerHandler.Result apply;
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

		apply = applyBatteryPowerControl(apply, isGlobalFilterEnabled, rctPower, cycleTime);

		// Get Read and Write Channels
		EnumReadChannel remoteControlSocStrategyReadChannel = rctPower.channel(RctPowerEss.ChannelId.SOC_STRATEGY);
		EnumWriteChannel remoteControlSocStrategyWriteChannel = rctPower.channel(RctPowerEss.ChannelId.SOC_STRATEGY);
		IntegerReadChannel remoteControlBatteryPowerReadChannel = rctPower.channel(RctPowerEss.ChannelId.BMS_BATTERY_POWER_EXTERN);
		IntegerWriteChannel remoteControlBatteryPowerWriteChannel = rctPower.channel(RctPowerEss.ChannelId.BMS_BATTERY_POWER_EXTERN);
		
		// Get active values
		var remoteControlSocStrategy = remoteControlSocStrategyReadChannel.value().asEnum();
		var remoteControlBatteryPower = remoteControlBatteryPowerReadChannel.value().orElse(0);
		
		// Write to SocStrategy Channel
		if (remoteControlSocStrategy != SocStrategy.UNDEFINED && remoteControlSocStrategy != apply.socStrategy())
			remoteControlSocStrategyWriteChannel.setNextWriteValue(apply.socStrategy());

		// Write to BatteryPowerExtern Channel (if the deviation is greater than MINIMUM_CHANGE_FOR_WRITE)
		if (Math.abs(remoteControlBatteryPower - apply.batteryPowerExtern()) > MINIMUM_CHANGE_FOR_WRITE
				|| (apply.batteryPowerExtern() == 0 && remoteControlBatteryPower != 0)) 
			remoteControlBatteryPowerWriteChannel.setNextWriteValue(apply.batteryPowerExtern());				
	}

	/**
	 * Applies the internal battery power control logic to the given {@link Result}.
	 *
	 * <p>This method transforms the requested battery power setpoint into a final
	 * command that can be sent to the inverter. It includes all local processing steps:
	 *
	 * <ul>
	 *   <li>Pre-limiting based on battery constraints (SoC and device limits)</li>
	 *   <li>Deadband to suppress small setpoints</li>
	 *   <li>Ramp limiting to constrain power changes per cycle</li>
	 *   <li>PT1 filter to smooth transitions</li>
	 *   <li>Post-deadband to eliminate small fluctuations around zero</li>
	 *   <li>Discharge efficiency compensation</li>
	 *   <li>Final safety clamp based on battery limits</li>
	 * </ul>
	 *
	 * <p>This method represents the complete local "smoothing and constraint" stage
	 * before writing the final values to the RctPower inverter.
	 *
	 * @param apply                 the requested battery power setpoint as {@link Result}
	 * @param isGlobalFilterEnabled true if an external/global filter is active
	 * @param maxAcImport           the maximum allowed AC import power [W]
	 * @param maxAcExport           the maximum allowed AC export power [W]
	 * @param cycleTime             the controller cycle time in milliseconds
	 * @return the final, smoothed and constrained {@link Result}
	 */
	private Result applyBatteryPowerControl(Result apply, boolean isGlobalFilterEnabled,
			RctPowerEss rctPower, int cycleTime) {

		// Apply battery limits before smoothing (prevent invalid targets entering the smoother)
		apply = this.applyBatteryLimits(rctPower, apply, false);

		// Apply deadband before smoothing (suppress small setpoints)
		apply = this.applyDeadband(isGlobalFilterEnabled, apply);

		// Apply internal ramp
		apply = this.applyMaxIncrease(isGlobalFilterEnabled, apply, cycleTime);

		// Apply internal PT1 filter
		apply = this.applyInternalFilter(isGlobalFilterEnabled, rctPower, apply);

		// Apply deadband after smoothing (suppress small fluctuations around 0)
		apply = this.applyDeadband(isGlobalFilterEnabled, apply);

		// Apply discharge-efficiency compensation
		apply = this.applyDischargeEfficencyCompensation(apply);

		// Enforce battery limits after smoothing (final safety clamp)
		apply = this.applyBatteryLimits(rctPower, apply, true);

		return apply;
	}

	/**
	 * Applies the deadband (filters battery charge/discharge if below MIN_BATTERY_POWER)
	 *
	 * @param isGlobalFilterEnabled is global {@link Filter} enabled?
	 * @param apply                 the calculated {@link Result
	 * @return the new value
	 */
	private Result applyDeadband(boolean isGlobalFilterEnabled, Result apply) {
		if (isGlobalFilterEnabled) {
			return apply;
		}

		if(apply.socStrategy() == SocStrategy.EXTERNAL && Math.abs(apply.batteryPowerExtern()) < MIN_BATTERY_POWER) {
			return new Result(SocStrategy.EXTERNAL, 0); 			
		}	

		return apply;
	}

	/**
	 * Applies the max increase ramp for CHARGE_BAT and DISCHARGE_BAT
	 *
	 * @param isGlobalFilterEnabled is global {@link Filter} enabled?
	 * @param apply                 the calculated {@link Result
	 * @param cycleTime             the cycle time
	 * @return the new value
	 */
	private Result applyMaxIncrease(boolean isGlobalFilterEnabled, Result apply, int cycleTime) {
		if (isGlobalFilterEnabled) {
			this.lastBatteryPower = null;
			return apply;
		}

		if(apply.socStrategy() == SocStrategy.EXTERNAL) {
			var batteryPower = applyMaxIncrease(this.lastBatteryPower, apply.batteryPowerExtern(), cycleTime);
			this.lastBatteryPower = batteryPower;
			return new Result(SocStrategy.EXTERNAL, batteryPower);			
		}
		
		this.lastBatteryPower = null;
		return apply;
	}

	/**
	 * Applies the max increase ramp, built from maxIncreasePerCycle.
	 *
	 * @param lastValue           the result value in [W] of previous run
	 * @param thisValue           the current value [W]
	 * @param maxIncreasePerCycle the maximum allowed value increase in [W] per cycle
	 * @return the new value
	 */
	private int applyMaxIncrease(Integer lastValue, Integer thisValue, int cycleTime) {
		if (lastValue == null) {
			return thisValue;
		}
		
		final int maxChargeIncreasePerCycle = /* max charge increase per cycle [W] */ MAX_CHARGE_INCREASE_PER_SECOND * cycleTime / 1000;
		final int maxDischargeIncreasePerCycle = /* max discharge increase per cycle [W] */ MAX_DISCHARGE_INCREASE_PER_SECOND * cycleTime / 1000;
		
		final int delta = thisValue - lastValue;
		if (delta == 0) {
			return thisValue;
		}
		
		/*
		 * delta > 0:
		 *   movement towards more discharge / less charge
		 * delta < 0:
		 *   movement towards more charge / less discharge
		 */
		final int maxDeltaPerCycle = delta > 0
				? maxDischargeIncreasePerCycle
				: maxChargeIncreasePerCycle;
		
		if (delta > maxDeltaPerCycle) {
			return lastValue + maxDeltaPerCycle;
		}
		if (delta < -maxDeltaPerCycle) {
			return lastValue - maxDeltaPerCycle;
		}
		return thisValue;
	}

	/**
	 * If {@link EmsPowerMode} is not {@link EmsPowerMode#AUTO}, apply fallback PID
	 * filter.
	 * 
	 * @param isGlobalFilterEnabled is global {@link Filter} enabled?
	 * @param essActivePower        the Active-Power Set-Point
	 * @param maxAcImport           the max AC import power
	 * @param maxAcExport           the max AC export power
	 * @param apply                 the calculated {@link Result}
	 * @return the filtered EMS-Power-Set value
	 */
	protected Result applyInternalFilter(boolean isGlobalFilterEnabled, RctPowerEss rctPower,
			Result apply) {
		return switch (apply.socStrategy()) {
		case SOC, CONSTANT, MIDDLE_BATTERY_VOLTAGE, INTERNAL, SCHEDULE, UNDEFINED -> {
			// If Filter is disabled, we still want to update the internal state of the
			// filter to avoid a big jump when enabling it.
			this.internalFilter.reset();
			yield apply;
		}

		case EXTERNAL -> {
			if (isGlobalFilterEnabled) {
				yield apply;
			}

			final Integer bmsVoltage = rctPower.getBmsVoltage().get();
			final Integer bmsMaxChargeCurrent = rctPower.getBmsChargeMaxCurrent().get();
			final Integer bmsMaxDischargeCurrent = rctPower.getBmsDischargeMaxCurrent().get();
			if (bmsVoltage == null || bmsMaxChargeCurrent == null || bmsMaxDischargeCurrent == null) {
				// Cannot apply filter without limits
				yield apply;
			}

			this.internalFilter.setLimits((bmsVoltage * bmsMaxChargeCurrent * -1), (bmsVoltage * bmsMaxDischargeCurrent));
			var filteredBatteryPowerSet = this.internalFilter.applyPT1Filter(apply.batteryPowerExtern());
			yield new Result(SocStrategy.EXTERNAL, filteredBatteryPowerSet);
		}
		};
	}

	/**
	 * Applies a discharge efficiency compensation to the battery power during discharge
	 *
	 * @param isGlobalFilterEnabled is global {@link Filter} enabled?
	 * @param apply                 the calculated {@link Result
	 * @return the new value
	 */
	private Result applyDischargeEfficencyCompensation(Result apply) {
		if(apply.socStrategy() == SocStrategy.EXTERNAL && apply.batteryPowerExtern() > 0) {
			return new Result(SocStrategy.EXTERNAL, (int) Math.floor(apply.batteryPowerExtern() / DISCHARGE_EFFICIENCY_FACTOR)); 			
		} else {
			return apply;
		}
	}

	/**
	 * Applies battery limits to the calculated result.
	 *
	 * @param rctPower the RctPower ESS
	 * @param apply    the calculated {@link Result}
	 * @return         the limited {@link Result}
	 */
	private Result applyBatteryLimits(RctPowerEss rctPower, Result apply, boolean resetFilter) {
		final Integer soc = rctPower.getSoc().get();
		final Integer minSoc = (Integer) rctPower.channel(RctPowerEss.ChannelId.SOC_MIN).value().get();
		final Integer maxSoc = (Integer) rctPower.channel(RctPowerEss.ChannelId.SOC_MAX).value().get();
		final Integer bmsVoltage = rctPower.getBmsVoltage().get();
		final Integer bmsMaxChargeCurrent = rctPower.getBmsChargeMaxCurrent().get();
		final Integer bmsMaxDischargeCurrent = rctPower.getBmsDischargeMaxCurrent().get();

		// Block charging if battery is full (SoC limit).
		// Charging direction is invalid -> force power to 0
		if(soc != null && maxSoc != null
				&& soc >= maxSoc
				&& apply.socStrategy() == SocStrategy.EXTERNAL
				&& apply.batteryPowerExtern() < 0) {
			// Reset internal filter when charging is blocked,
			// so the filter state does not continue in an invalid direction
			if(resetFilter) this.internalFilter.reset();
			return new Result(SocStrategy.EXTERNAL, 0);
		}

		// Block discharging if battery is empty (SoC limit).
		// Discharging direction is invalid -> force power to 0
		if(soc != null && minSoc != null
				&& soc <= minSoc
				&& apply.socStrategy() == SocStrategy.EXTERNAL
				&& apply.batteryPowerExtern() > 0) {
			// Reset internal filter when discharging is blocked,
			// so the filter state does not continue in an invalid direction
			if(resetFilter) this.internalFilter.reset();
			return new Result(SocStrategy.EXTERNAL, 0);
		}

		// Enforce battery limits.
		// Do NOT reset internal filter when clamping to maxCharge/maxDischarge,
		// as only the power level is limited and direction remains valid.
		// Reset is only required if charge/discharge is fully blocked (e.g. SoC limits).
		if (bmsVoltage != null && bmsMaxChargeCurrent != null
				&& apply.socStrategy() == SocStrategy.EXTERNAL
				&& apply.batteryPowerExtern() < (bmsVoltage * bmsMaxChargeCurrent * -1)) {
			return new Result(apply.socStrategy(), (bmsVoltage * bmsMaxChargeCurrent * -1)); // limit to batteryChargeLimit
		}
		if (bmsVoltage != null && bmsMaxDischargeCurrent != null
				&& apply.socStrategy() == SocStrategy.EXTERNAL
				&& apply.batteryPowerExtern() > (bmsVoltage * bmsMaxDischargeCurrent)) {
			return new Result(apply.socStrategy(), (bmsVoltage * bmsMaxDischargeCurrent)); // limit to batteryDischargeLimit
		}

		return apply;
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
		// TODO PV curtail: (surplus power == setpoint && battery soc == 100% => PV
		// curtail)
		if (activePowerSetPoint < 0) {
			// Set-Point is negative -> charge battery with all PV + requested AC-Charge 
			var result = activePowerSetPoint - pvProduction;
			return new Result(SocStrategy.EXTERNAL, result);
		}
		if (pvProduction >= activePowerSetPoint) {
			// Set-Point is positive && less than PV-Production -> feed PV partly to grid +
			// charge battery
			// On Surplus Feed-In PV == Set-Point => CHARGE_BAT 0
			var result = activePowerSetPoint-pvProduction;
			return new Result(SocStrategy.EXTERNAL, result);
		} else {
			// Set-Point is positive && bigger than PV-Production -> feed all PV to grid +
			// discharge battery
			var result = activePowerSetPoint-pvProduction;
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