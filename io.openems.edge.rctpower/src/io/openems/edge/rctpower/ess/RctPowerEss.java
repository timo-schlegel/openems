package io.openems.edge.rctpower.ess;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.rctpower.enums.SocStrategy;
import io.openems.edge.rctpower.charger.RctPowerCharger;
import io.openems.edge.rctpower.enums.AcCharge;
import io.openems.edge.rctpower.enums.BatteryStatus;
import io.openems.edge.rctpower.enums.InverterStatus;
import io.openems.edge.rctpower.enums.MeterCommunicateStatus;

public interface RctPowerEss extends OpenemsComponent, SymmetricEss {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {	
		SMART_MODE_NOT_WORKING_WITH_PID_FILTER(Doc.of(Level.WARNING) //
				.text("SMART mode does not work correctly with active PID filter")),		
		NO_SMART_METER_DETECTED(Doc.of(Level.WARNING) //
				.text("No RctPower Smart Meter detected. Only REMOTE mode can work correctly")),
		AC_CHARGE_NOT_ENABLED(Doc.of(Level.WARNING) //
				.text("Utilize external Inverter energy is not enabled. Please configure inverter")),		
		PV_EXPORT_LIMIT_FAILED(Doc.of(Level.FAULT) //
				.text("PV-Export Limit failed")), //
		DISABLED_PV_EXPORT_LIMIT_FAILED(Doc.of(Level.WARNING) //
				.text("PV-Export Limit is disabled: PV-Export Limit failed")), //
		INVERTER_STATUS(Doc.of(InverterStatus.values()).persistencePriority(PersistencePriority.HIGH)),

		/**
		 * Read/Set Active Export Power Limit.
		 *
		 * <ul>
		 * <li>Interface: FeedToGridLimitEss
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		ACTIVE_EXPORT_POWER_LIMIT(new IntegerDoc() //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.READ_WRITE) //
				.persistencePriority(PersistencePriority.MEDIUM) //
				.onInit(channel -> { //
					// on each Write to the channel -> set the value
					((IntegerWriteChannel) channel).onSetNextWrite(value -> {
						channel.setNextValue(value);
					});
				})),	
		
		/**
		 * Battery State Of Health.
		 *
		 * <ul>
		 * <li>Interface: RctPowerEss
		 * <li>Type: Integer
		 * <li>Unit: Percent
		 * <li>
		 * </ul>
		 */
		SOH(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT) //
				.persistencePriority(PersistencePriority.LOW)),		
		
		SOC_MIN(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_ONLY)),
		SOC_MAX(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_ONLY)),
		SOC_STRATEGY(Doc.of(SocStrategy.values()).accessMode(AccessMode.READ_WRITE)),
		BMS_CHARGE_MAX_CURRENT(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_ONLY)),		// Read FLOAT into INTEGER CHANNEL
		BMS_DISCHARGE_MAX_CURRENT(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_ONLY)),	// Read FLOAT into INTEGER CHANNEL
		BMS_VOLTAGE(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_ONLY)),					// Read FLOAT into INTEGER CHANNEL
		BMS_ALLOW_AC_CHARGE(Doc.of(AcCharge.values()).accessMode(AccessMode.READ_ONLY)),
		BMS_BATTERY_STATUS(Doc.of(BatteryStatus.values()).accessMode(AccessMode.READ_ONLY)),
		BMS_BATTERY_POWER_EXTERN(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_WRITE)),
		
		// External power reduction based on solar plant peak power [0..1] - will be set from SetPvExportLimitHandler
		POWER_REDUCTION(Doc.of(OpenemsType.FLOAT).accessMode(AccessMode.READ_WRITE).persistencePriority(PersistencePriority.HIGH)),
		
		// Solar plant peak power
		POWER_REDUCTION_MAX_SOLAR(Doc.of(OpenemsType.FLOAT).accessMode(AccessMode.READ_ONLY)),		

		METER_COMMUNICATE_STATUS(Doc.of(MeterCommunicateStatus.values())), //		
		;

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Gets the Channel for {@link ChannelId#ACTIVE_EXPORT_POWER_LIMIT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getActiveExportPowerLimitChannel() {
		return this.channel(ChannelId.ACTIVE_EXPORT_POWER_LIMIT);
	}

	/**
	 * Gets the Active Export Power Limit in [W]. See {@link ChannelId#ACTIVE_EXPORT_POWER_LIMIT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getActiveExportPowerLimit() {
		return this.getActiveExportPowerLimitChannel().value();
	}

	/**
	 * Sets the Active Export Power Limit in [W]. See {@link ChannelId#ACTIVE_EXPORT_POWER_LIMIT}.
	 *
	 * @param value the Integer value
	 * @throws OpenemsNamedException on error
	 */
	public default void setActiveExportPowerLimit(Integer value) throws OpenemsNamedException {
		this.getActiveExportPowerLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Sets the Active Export Power Limit in [W]. See {@link ChannelId#ACTIVE_EXPORT_POWER_LIMIT}.
	 *
	 * @param value the int value
	 * @throws OpenemsNamedException on error
	 */
	public default void setActiveExportPowerLimit(int value) throws OpenemsNamedException {
		this.getActiveExportPowerLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#BMS_CHARGE_MAX_CURRENT}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getBmsChargeMaxCurrentChannel() {
		return this.channel(ChannelId.BMS_CHARGE_MAX_CURRENT);
	}

	/**
	 * Gets the Bms Charge Max Current in [A]. See
	 * {@link ChannelId#BMS_CHARGE_MAX_CURRENT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getBmsChargeMaxCurrent() {
		return this.getBmsChargeMaxCurrentChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#BMS_DISCHARGE_MAX_CURRENT}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getBmsDischargeMaxCurrentChannel() {
		return this.channel(ChannelId.BMS_DISCHARGE_MAX_CURRENT);
	}

	/**
	 * Gets the Bms Discharge Max Current in [A]. See
	 * {@link ChannelId#BMS_DISCHARGE_MAX_CURRENT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getBmsDischargeMaxCurrent() {
		return this.getBmsDischargeMaxCurrentChannel().value();
	}

	/**
	 * Gets the Channel for {@link ChannelId#BMS_VOLTAGE}.
	 *
	 * @return the Channel
	 */
	public default IntegerReadChannel getBmsVoltageChannel() {
		return this.channel(ChannelId.BMS_VOLTAGE);
	}

	/**
	 * Gets the Bms voltage in [V]. See {@link ChannelId#BMS_VOLTAGE}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getBmsVoltage() {
		return this.getBmsVoltageChannel().value();
	}
	
	/**
	 * Adds DC-charger to ESS hybrid system. Represents PV production
	 * 
	 * @param charger link to DC charger(s)
	 */
	public void addCharger(RctPowerCharger charger);

	/**
	 * Removes link to pv DC charger.
	 * 
	 * @param charger charger
	 */
	public void removeCharger(RctPowerCharger charger);
	
	/**
	 * Gets the RctPower-Bridge Component-ID, i.e. "rctpower0".
	 *
	 * @return the Component-ID
	 */
	public String getRctpowerBridgeId();	
	
	/**
	 * Gets the PV production from chargers ACTUAL_POWER. Returns null if the PV
	 * production is not available.
	 *
	 * @return production power
	 */
	public Integer getPvProduction();

	/**
	 * Gets Surplus Power.
	 *
	 * @return {@link Integer}
	 */
	public Integer getSurplusPower();		
}
