package io.openems.edge.rctpower.ess;

import static io.openems.common.utils.IntUtils.sumInteger;
import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.rctpower.bridge.api.ElementToChannelConverter.DIRECT_1_TO_1;
import static io.openems.edge.rctpower.bridge.api.ElementToChannelConverter.SCALE_FACTOR_2;

import java.util.HashSet;
import java.util.Set;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.cycle.Cycle;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.rctpower.bridge.api.AbstractOpenemsRctComponent;
import io.openems.edge.rctpower.bridge.api.BridgeRctPower;
import io.openems.edge.rctpower.bridge.api.ElementToChannelConverter;
import io.openems.edge.rctpower.bridge.api.RctComponent;
import io.openems.edge.rctpower.bridge.api.RctProtocol;
import io.openems.edge.rctpower.bridge.api.element.FloatElement;
import io.openems.edge.rctpower.bridge.api.element.SignedDoublewordElement;
import io.openems.edge.rctpower.bridge.api.element.UnsignedByteElement;
import io.openems.edge.rctpower.bridge.api.task.ReadObjectTask;
import io.openems.edge.rctpower.bridge.api.task.WriteObjectTask;
import io.openems.edge.rctpower.charger.RctPowerCharger;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "RctPower.ESS", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
	EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
	EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE //
})
public class RctPowerEssImpl extends AbstractOpenemsRctComponent implements RctPowerEss, ManagedSymmetricEss, SymmetricEss,
				HybridEss, RctComponent, OpenemsComponent, EventHandler, TimedataProvider {

	private final AllowedChargeDischargeHandler allowedChargeDischargeHandler = new AllowedChargeDischargeHandler(this);	
	private final ApplyPowerHandler applyPowerHandler = new ApplyPowerHandler();
	private final SetPvExportLimitHandler setPvExportLimitHandler = new SetPvExportLimitHandler(this);
	
	protected final Set<RctPowerCharger> chargers = new HashSet<>();
	
	private Config config = null;
	
	public static final ElementToChannelConverter FLOAT_CONVERTER = new ElementToChannelFloatConverter();
	
	private final ElementToChannelConverter ignoreMinPower = IgnoreMinPowerConverter.from(this, DIRECT_1_TO_1);
	
	private final CalculateEnergyFromPower calculateAcChargeEnergy = new CalculateEnergyFromPower(this, SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateAcDischargeEnergy = new CalculateEnergyFromPower(this, SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDcChargeEnergy = new CalculateEnergyFromPower(this, HybridEss.ChannelId.DC_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDcDischargeEnergy = new CalculateEnergyFromPower(this, HybridEss.ChannelId.DC_DISCHARGE_ENERGY);
	
	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;
	
	@Reference
	private Cycle cycle;	
	
	@Reference
	private ConfigurationAdmin cm;
	
	@Reference
	private Power power;
	
	@Reference
	private Sum sum;	
	
	@Reference
	private ComponentManager componentManager;
	
	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setRctpower(BridgeRctPower rctpower) {
		super.setRctpower(rctpower);
	}	
	
	public RctPowerEssImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				RctComponent.ChannelId.values(), //
				HybridEss.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				RctPowerEss.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;
		if (super.activate(context, config.id(), config.alias(), config.enabled(), this.cm,
				"Rctpower", config.rctpower_id())) {
				return;
		}
		this._setCapacity(this.config.capacity());
		setValue(this, SymmetricEss.ChannelId.GRID_MODE, GridMode.ON_GRID);
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}	

	@Override
	protected final RctProtocol defineRctProtocol() {
		var rctProtocol = new RctProtocol(this, //			
				new ReadObjectTask(0x4E49AEC5, Priority.HIGH, m(SymmetricEss.ChannelId.ACTIVE_POWER, new FloatElement(0x4E49AEC5), this.ignoreMinPower)),
				new ReadObjectTask(0x7C78CBAC, Priority.HIGH, m(SymmetricEss.ChannelId.REACTIVE_POWER, new FloatElement(0x7C78CBAC))),
				new ReadObjectTask(0x9A67600D, Priority.LOW, m(SymmetricEss.ChannelId.MAX_APPARENT_POWER, new FloatElement(0x9A67600D))),
				new ReadObjectTask(0x1156DFD0, Priority.HIGH, m(HybridEss.ChannelId.DC_DISCHARGE_POWER, new FloatElement(0x1156DFD0))),
				new ReadObjectTask(0x5F33284E, Priority.HIGH, m(RctPowerEss.ChannelId.INVERTER_STATUS, new UnsignedByteElement(0x5F33284E))),
				
				new ReadObjectTask(0x381B8BF9, Priority.LOW, m(RctPowerEss.ChannelId.SOH, new FloatElement(0x381B8BF9), FLOAT_CONVERTER)),
				new ReadObjectTask(0x959930BF, Priority.LOW, m(SymmetricEss.ChannelId.SOC, new FloatElement(0x959930BF), FLOAT_CONVERTER)),
				new ReadObjectTask(0xCE266F0F, Priority.LOW, m(RctPowerEss.ChannelId.SOC_MIN, new FloatElement(0xCE266F0F), SCALE_FACTOR_2)),
				new ReadObjectTask(0x97997C93, Priority.LOW, m(RctPowerEss.ChannelId.SOC_MAX, new FloatElement(0x97997C93), SCALE_FACTOR_2)),
				new ReadObjectTask(0xF168B748, Priority.LOW, m(RctPowerEss.ChannelId.SOC_STRATEGY, new UnsignedByteElement(0xF168B748))),
				new ReadObjectTask(0x1E5FCA70, Priority.LOW, m(RctPowerEss.ChannelId.BMS_CHARGE_MAX_CURRENT, new FloatElement(0x1E5FCA70))),
				new ReadObjectTask(0xDF0A735C, Priority.LOW, m(RctPowerEss.ChannelId.BMS_DISCHARGE_MAX_CURRENT, new FloatElement(0xDF0A735C))),
				new ReadObjectTask(0xA7FA5C5D, Priority.LOW, m(RctPowerEss.ChannelId.BMS_VOLTAGE, new FloatElement(0xA7FA5C5D))),
				new ReadObjectTask(0x36A9E9A6, Priority.LOW, m(RctPowerEss.ChannelId.BMS_ALLOW_AC_CHARGE, new UnsignedByteElement(0x36A9E9A6))),
				new ReadObjectTask(0x70A2AF4F, Priority.LOW, m(RctPowerEss.ChannelId.BMS_BATTERY_STATUS, new SignedDoublewordElement(0x70A2AF4F))),
				new ReadObjectTask(0xBD008E29, Priority.HIGH, m(RctPowerEss.ChannelId.BMS_BATTERY_POWER_EXTERN, new FloatElement(0xBD008E29))),
				
				new ReadObjectTask(0xFE1AA500, Priority.LOW, m(RctPowerEss.ChannelId.POWER_REDUCTION, new FloatElement(0xFE1AA500))),
				new ReadObjectTask(0x4BC0F974, Priority.LOW, m(RctPowerEss.ChannelId.POWER_REDUCTION_MAX_SOLAR, new FloatElement(0x4BC0F974))),
				
				new ReadObjectTask(0xC3A3F070, Priority.LOW, m(RctPowerEss.ChannelId.METER_COMMUNICATE_STATUS, new UnsignedByteElement(0xC3A3F070))),
				new ReadObjectTask(0x62FBE7DC, Priority.LOW, m(SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY, new FloatElement(0x62FBE7DC))),
				new ReadObjectTask(0x44D4C533, Priority.LOW, m(SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY, new FloatElement(0x44D4C533))),
				new ReadObjectTask(0x5570401B, Priority.LOW, m(HybridEss.ChannelId.DC_CHARGE_ENERGY, new FloatElement(0x5570401B))),
				new ReadObjectTask(0xA9033880, Priority.LOW, m(HybridEss.ChannelId.DC_DISCHARGE_ENERGY, new FloatElement(0xA9033880))),				
				
				new WriteObjectTask(0xF168B748, m(RctPowerEss.ChannelId.SOC_STRATEGY, new UnsignedByteElement(0xF168B748))),
				new WriteObjectTask(0xBD008E29, m(RctPowerEss.ChannelId.BMS_BATTERY_POWER_EXTERN, new FloatElement(0xBD008E29)))
				//new WriteObjectTask(0xFE1AA500, m(RctPowerEss.ChannelId.POWER_REDUCTION, new FloatElement(0xFE1AA500)))
				);
		
		return rctProtocol;
	}	
	
	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}

		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> {
			//this.logInfo(log, "before process image");
			this.allowedChargeDischargeHandler.accept(this.componentManager);
			this.updateEnergyChannels();
		}
		case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE -> {
			//this.logInfo(log, "on execute write");
			// Get ActiveExportPowerLimit that should be applied
			var activeExportPowerLimitChannel = (IntegerWriteChannel) this
					.channel(RctPowerEss.ChannelId.ACTIVE_EXPORT_POWER_LIMIT);
			var activeExportPowerLimitOpt = activeExportPowerLimitChannel.getNextWriteValueAndReset();			
			
			// Set warning if pvExportLimit mode is disabled but a PV export limit was requested
			this.channel(RctPowerEss.ChannelId.DISABLED_PV_EXPORT_LIMIT_FAILED)
					.setNextValue(!this.config.pvExportLimit() && activeExportPowerLimitOpt.isPresent());
	
			// If pvExportLimit mode is disabled: stop here
			if (!this.config.pvExportLimit()) {
				return;
			}
	
			try {
				this.setPvExportLimitHandler.accept(activeExportPowerLimitOpt);
	
				this.channel(RctPowerEss.ChannelId.PV_EXPORT_LIMIT_FAILED).setNextValue(false);
			} catch (OpenemsNamedException e) {
				this.channel(RctPowerEss.ChannelId.PV_EXPORT_LIMIT_FAILED).setNextValue(true);
			}
		}
		}		
	}

	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().asString() //
				+ "|L:" + this.getActivePower().asString()
				+ "|Allowed:" + this.getAllowedChargePower().asStringWithoutUnit() + ";"
				+ this.getAllowedDischargePower().asString()
				+ "|SocStrategy:" + this.channel(RctPowerEss.ChannelId.SOC_STRATEGY).value().asOptionString()
				+ "|BmsChargeImax:" + this.channel(RctPowerEss.ChannelId.BMS_CHARGE_MAX_CURRENT).value().asString()
				+ "|BmsDischargeImax:" + this.channel(RctPowerEss.ChannelId.BMS_DISCHARGE_MAX_CURRENT).value().asString()
				+ "|BmsVoltage:" + this.channel(RctPowerEss.ChannelId.BMS_VOLTAGE).value().asString()
				+ "|BatteryPower:" + this.channel(HybridEss.ChannelId.DC_DISCHARGE_POWER).value().asString()
				+ "|BatteryPowerExtern:" + this.channel(RctPowerEss.ChannelId.BMS_BATTERY_POWER_EXTERN).value().asString()				
				+ "|BatteryStatus:" + this.channel(RctPowerEss.ChannelId.BMS_BATTERY_STATUS).value().asOptionString()
				+ "|InverterStatus:" + this.channel(RctPowerEss.ChannelId.INVERTER_STATUS).value().asOptionString();
	}
	
	@Override
	public void addCharger(RctPowerCharger charger) {
		this.chargers.add(charger);
	}

	@Override
	public void removeCharger(RctPowerCharger charger) {
		this.chargers.remove(charger);
	}	
	
	public String getRctpowerBridgeId() {
		return this.config.rctpower_id();
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		// Apply Power Set-Point
		this.applyPowerHandler.apply(this, activePower, this.config.controlMode(), this.sum.getGridActivePower(),
				this.getActivePower(), this.power.isFilterEnabled());
	}	

	@Override
	public int getPowerPrecision() {
		return 1;
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public Integer getSurplusPower() {
		// TODO logic is insufficient
		IntegerReadChannel maxSocChannel = this.channel(RctPowerEss.ChannelId.SOC_MAX);
		if (this.getSoc().orElse(0) < maxSocChannel.value().orElse(97)-1) {
			return null;
		}
		var productionPower = this.getPvProduction();
		if (productionPower == null || productionPower < 100) {
			return null;
		}
		return productionPower;
	}

	/**
	 * Gets the PV production from chargers ACTUAL_POWER. Returns null if the PV
	 * production is not available.
	 *
	 * @return production power
	 */
	public Integer getPvProduction() {
		Integer productionPower = null;
		for (RctPowerCharger charger : this.chargers) {
			productionPower = sumInteger(productionPower, charger.getActualPower().get());
		}
		return productionPower;
	}
	
	protected void updateEnergyChannels() {

		/*
		 * Calculate AC Energy
		 */
		var acActivePower = this.getActivePower().get();
		if (acActivePower == null) {
			// Not available
			this.calculateAcChargeEnergy.update(null);
			this.calculateAcDischargeEnergy.update(null);
		} else if (acActivePower > 0) {
			// Discharge
			this.calculateAcChargeEnergy.update(0);
			this.calculateAcDischargeEnergy.update(acActivePower);
		} else {
			// Charge
			this.calculateAcChargeEnergy.update(acActivePower * -1);
			this.calculateAcDischargeEnergy.update(0);
		}
		
		/*
		 * Calculate DC Energy
		 */
		var dcDischargePower = this.getDcDischargePower().get();
		if (dcDischargePower == null) {
			// Not available
			this.calculateDcChargeEnergy.update(null);
			this.calculateDcDischargeEnergy.update(null);
		} else if (dcDischargePower > 0) {
			// Discharge
			this.calculateDcChargeEnergy.update(0);
			this.calculateDcDischargeEnergy.update(dcDischargePower);
		} else {
			// Charge
			this.calculateDcChargeEnergy.update(dcDischargePower * -1);
			this.calculateDcDischargeEnergy.update(0);
		}		
	}	
}
