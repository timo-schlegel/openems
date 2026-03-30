package io.openems.edge.rctpower.ess;

import static io.openems.common.utils.IntUtils.sumInteger;
import static io.openems.edge.common.channel.ChannelUtils.setValue;

import io.openems.edge.common.channel.EnumReadChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.ClockProvider;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.generic.common.AbstractAllowedChargeDischargeHandler;
import io.openems.edge.rctpower.charger.RctPowerCharger;
import io.openems.edge.rctpower.enums.BatteryStatus;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;

public class AllowedChargeDischargeHandler extends AbstractAllowedChargeDischargeHandler<RctPowerEssImpl> {

	public AllowedChargeDischargeHandler(RctPowerEssImpl parent) {
		super(parent);
	}

	@Override
	public void accept(ClockProvider clockProvider, Battery battery, SymmetricBatteryInverter inverter) {
		this.accept(clockProvider);
	}

	/**
	 * Calculates AllowedChargePower and AllowedDischargePower and sets the
	 * Channels.
	 *
	 * @param clockProvider a {@link ClockProvider}
	 */
	public void accept(ClockProvider clockProvider) {
		IntegerReadChannel bmsChargeImaxChannel = parent.channel(RctPowerEss.ChannelId.BMS_CHARGE_MAX_CURRENT);
		var bmsChargeImax = bmsChargeImaxChannel.getNextValue().get();
		IntegerReadChannel bmsDischargeImaxChannel = parent.channel(RctPowerEss.ChannelId.BMS_DISCHARGE_MAX_CURRENT);
		var bmsDischargeImax = bmsDischargeImaxChannel.getNextValue().get();
		IntegerReadChannel bmsVoltageChannel = parent.channel(RctPowerEss.ChannelId.BMS_VOLTAGE);
		var bmsVoltage = bmsVoltageChannel.getNextValue().get();
		this.calculateAllowedChargeDischargePower(clockProvider, true, bmsChargeImax, bmsDischargeImax, bmsVoltage);
		
		// Battery limits
		var batteryAllowedChargePower = Math.round(this.lastBatteryAllowedChargePower);
		var batteryAllowedDischargePower = Math.round(this.lastBatteryAllowedDischargePower);		
		
		// Inverter limits
		var maxApparentPower = parent.getMaxApparentPower().orElse(0);
		
		// PV-Production
		Integer pvProduction = 0;
		for (RctPowerCharger charger : parent.chargers) {
			pvProduction = sumInteger(pvProduction, charger.getActualPowerChannel().getNextValue().orElse(0));
		}
		
		// Block battery charging on battery full
		IntegerReadChannel maxSocChannel = parent.channel(RctPowerEss.ChannelId.SOC_MAX);
		if(parent.getSoc().orElse(100) >= maxSocChannel.value().orElse(0))
			batteryAllowedChargePower = 0;
		
		// Block battery discharging on battery empty
		IntegerReadChannel minSocChannel = parent.channel(RctPowerEss.ChannelId.SOC_MIN);
		if(parent.getSoc().orElse(0) <= minSocChannel.value().orElse(100))
			batteryAllowedDischargePower = 0;			
		
		// Check Battery Status (block charge/discharge if battery status is not NORMAL, e.g. on battery calibration)
		EnumReadChannel bmsBatteryStatus = parent.channel(RctPowerEss.ChannelId.BMS_BATTERY_STATUS);		
		if(bmsBatteryStatus.getNextValue().get() == null || bmsBatteryStatus.getNextValue().asEnum() != BatteryStatus.NORMAL) {
			batteryAllowedChargePower = 0;
			batteryAllowedDischargePower = 0;
		}	
		
		// Calculates Maximum Allowed AC-Charge Power as positive numbers (or negative when force discharge is active)
		//   Force discharge: pvProduction>batteryAllowedChargePower requires a minimum discharge 
		var acAllowedChargePower = batteryAllowedChargePower - pvProduction;
		
		// Calculates Maximum Allowed AC-Discharge Power as positive numbers
		var acAllowedDischargePower = Math.min(batteryAllowedDischargePower + pvProduction,parent.getMaxApparentPower().orElse(0));
		
		// Force Discharge active?
		if (acAllowedChargePower < 0) {
			
			// Limit forced DischargePower to maxApparentPower
			if(Math.abs(acAllowedChargePower)>maxApparentPower) acAllowedChargePower = maxApparentPower*(-1);
			
			// Make sure AllowedDischargePower is greater-or-equals absolute AllowedChargePower
			acAllowedDischargePower = Math.max(Math.abs(acAllowedChargePower), acAllowedDischargePower);
		} else {
			
			// Limit acChargerPower to maxApparentPower
			if(acAllowedChargePower>maxApparentPower) acAllowedChargePower = maxApparentPower;			
		}

		// Apply AllowedChargePower and AllowedDischargePower
		setValue(this.parent, ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER, acAllowedChargePower * -1 /* invert charge power */);
		setValue(this.parent, ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER, acAllowedDischargePower);
	}
	
}
