package io.openems.edge.rctpower.charger;

import static io.openems.edge.rctpower.bridge.api.ElementToChannelConverter.DIRECT_1_TO_1;

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
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.dccharger.api.EssDcCharger;
import io.openems.edge.rctpower.bridge.api.AbstractOpenemsRctComponent;
import io.openems.edge.rctpower.bridge.api.BridgeRctPower;
import io.openems.edge.rctpower.bridge.api.ElementToChannelConverter;
import io.openems.edge.rctpower.bridge.api.RctComponent;
import io.openems.edge.rctpower.bridge.api.RctProtocol;
import io.openems.edge.rctpower.bridge.api.element.FloatElement;
import io.openems.edge.rctpower.bridge.api.task.ReadObjectTask;
import io.openems.edge.rctpower.ess.RctPowerEss;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;

@Designate(ocd = ConfigB.class, factory = true)
@Component(//
		name = "RctPower.ESS.ChargerB", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class RctPowerChargerB extends AbstractOpenemsRctComponent
		implements RctPowerCharger, EssDcCharger, RctComponent, OpenemsComponent, TimedataProvider, ModbusSlave {

	private final ElementToChannelConverter ignoreMinPower = IgnoreMinPowerConverter.from(this, DIRECT_1_TO_1);

	@Reference
	private ConfigurationAdmin cm;
	
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	private RctPowerEss ess;
	
	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata;	
	
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setRctpower(BridgeRctPower rctpower) {
		super.setRctpower(rctpower);
	}	
	
	public RctPowerChargerB() throws OpenemsException {
		super(//
				OpenemsComponent.ChannelId.values(), //
				RctComponent.ChannelId.values(), //
				EssDcCharger.ChannelId.values(), //
				RctPowerCharger.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, ConfigA config) throws OpenemsException {
		if(super.activate(context, config.id(), config.alias(), config.enabled(), this.cm,
				"Rctpower", this.ess.getRctpowerBridgeId())) {
			return;
		}
		
		// update filter for 'Ess'
		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "ess",
				config.essInverter_id())) {
			return;
		}
		
		this.ess.addCharger(this);
	}
	
	@Override
	@Deactivate
	protected void deactivate() {
		this.ess.removeCharger(this);
		super.deactivate();
	}
	
	@Override
	protected RctProtocol defineRctProtocol() {
		var rctProtocol = new RctProtocol(this, //
				new ReadObjectTask(0xCB5D21B, Priority.HIGH, m(EssDcCharger.ChannelId.ACTUAL_POWER, new FloatElement(0xCB5D21B), this.ignoreMinPower)), // dc_conv.dc_conv_struct[1].p_dc_lp
				new ReadObjectTask(0x68EEFD3D, Priority.LOW, m(EssDcCharger.ChannelId.ACTUAL_ENERGY, new FloatElement(0x68EEFD3D))) // energy.e_dc_total[1]
				);
		
		return rctProtocol;
	}
	
	@Override
	public String debugLog() {
		return "P:" + this.getActualPower().asString();
	}	

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}
	
	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				EssDcCharger.getModbusSlaveNatureTable(accessMode), //
				ModbusSlaveNatureTable.of(RctPowerCharger.class, accessMode, 100) //
						.build());
	}

}
