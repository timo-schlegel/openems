package io.openems.edge.rctpower.bridge;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.utils.InetAddressUtils;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.rctpower.bridge.api.AbstractRctPowerBridge;
import io.openems.edge.rctpower.bridge.api.BridgeRctPower;
import io.openems.edge.rctpower.bridge.api.Config;
import io.openems.edge.rctpower.bridge.api.RctFrame;
import io.openems.edge.rctpower.bridge.api.RctTCPConnection;
import io.openems.edge.rctpower.bridge.api.RctTransaction;

/**
 * Provides a service for connecting to, querying and writing to a RctPower
 * device.
 */
@Designate(ocd = ConfigTcp.class, factory = true)
@Component(//
		name = "Bridge.RctPower", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE //
})
public class BridgeRctPowerImpl extends AbstractRctPowerBridge
		implements BridgeRctPower, OpenemsComponent, EventHandler {

	/** The configured IP address. */
	private InetAddress ipAddress = null;
	private int port;

	public BridgeRctPowerImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				BridgeRctPower.ChannelId.values() //
		);
	}
	
	@Activate
	private void activate(ComponentContext context, ConfigTcp config) throws UnknownHostException {
		super.activate(context, new Config(config.id(), config.alias(), config.enabled(), config.logVerbosity(),
				config.invalidateElementsAfterReadErrors()));
		this.applyConfig(config);
	}
	
	@Modified
	private void modified(ComponentContext context, ConfigTcp config) throws UnknownHostException {
		super.modified(context, new Config(config.id(), config.alias(), config.enabled(), config.logVerbosity(),
				config.invalidateElementsAfterReadErrors()));
		this.applyConfig(config);
		this.closeRctConnection();
	}

	private void applyConfig(ConfigTcp config) {
		this.setIpAddress(InetAddressUtils.parseOrNull(config.ip()));
		this.port = config.port();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}
	
	public void closeRctConnection() {
		if (this._connection != null) {
			this._connection.close();
			this._connection = null;
		}
	}
	
	@Override
	public RctTransaction getNewRctTransaction(RctFrame frame) throws OpenemsException {
		var connection = this.getRctTCPConnection();
		var transaction = new RctTransaction(connection, frame);
		transaction.setRetries(AbstractRctPowerBridge.DEFAULT_RETRIES);
		return transaction;
	}
	
	private RctTCPConnection _connection = null;

	private synchronized RctTCPConnection getRctTCPConnection() throws OpenemsException {
		if (this._connection == null) {
			/*
			 * create new connection
			 */
			var connection = new RctTCPConnection(this.getIpAddress());
			connection.setPort(this.port);
			this._connection = connection;
		}
		if (!this._connection.isConnected()) {
			try {
				this._connection.connect();
			} catch (Exception e) {
				throw new OpenemsException(
						"Connection to [" + this.getIpAddress().getHostAddress() + "] failed: " + e.getMessage());
			}
			this._connection.getRctTCPTransport().setTimeout(AbstractRctPowerBridge.DEFAULT_TIMEOUT);
		}
		return this._connection;
	}

	@Override
	public InetAddress getIpAddress() {
		return this.ipAddress;
	}

	public void setIpAddress(InetAddress ipAddress) {
		this.ipAddress = ipAddress;
	}	

}