package io.openems.edge.rctpower.bridge;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import io.openems.edge.rctpower.bridge.api.RctBatchTransaction;
import io.openems.edge.rctpower.bridge.api.RctFrame;
import io.openems.edge.rctpower.bridge.api.RctRequest;
import io.openems.edge.rctpower.bridge.api.RctResponse;
import io.openems.edge.rctpower.bridge.api.RctTCPConnection;
import io.openems.edge.rctpower.bridge.api.RctTransaction;
import io.openems.edge.rctpower.bridge.api.worker.RctReadWorker;

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
	private volatile boolean periodicalsDisabledForCurrentConnection = false;

	public BridgeRctPowerImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				BridgeRctPower.ChannelId.values() //
		);
		this.readWorker = new RctReadWorker(() -> {
			try {
				return this.getRctTCPConnection().getRctTCPTransport();
			} catch (OpenemsException e) {
				return null;
			}
		});
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
		return new RctTransaction(this.getRctTCPConnection(), frame, this.readWorker);
	}

	@Override
	public RctBatchTransaction getNewRctBatchTransaction(List<RctRequest> requests) throws OpenemsException {
		return new RctBatchTransaction(this.getRctTCPConnection(), requests, this.readWorker);
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

			// neue Connection -> Setup noch nicht gemacht
			this.periodicalsDisabledForCurrentConnection = false;
		}
		if (!this._connection.isConnected()) {
			try {
				this._connection.ensureConnected();
			} catch (Exception e) {
				throw new OpenemsException(
						"Connection to [" + this.getIpAddress().getHostAddress() + "] failed: " + e.getMessage());
			}
			this._connection.getRctTCPTransport().setTimeout(AbstractRctPowerBridge.DEFAULT_TIMEOUT);

			// reconnect -> Setup neu nötig
			this.periodicalsDisabledForCurrentConnection = false;

			if (this.disablePeriodicalSendings()) {
				this.disableRctPeriodicalSendings();
			}
		}
		return this._connection;
	}

	private static byte[] oid(int value) {
		return new byte[] {
			(byte) ((value >> 24) & 0xFF),
			(byte) ((value >> 16) & 0xFF),
			(byte) ((value >> 8) & 0xFF),
			(byte) (value & 0xFF)
		};
	}

	private void disableRctPeriodicalSendings() throws OpenemsException {
		if (this.periodicalsDisabledForCurrentConnection) {
			return;
		}

		//this.logInfo(this.log, "Disable RCT periodic sendings (pas.period=0)");
		//System.out.println("******* Disable RCT periodic sendings (pas.period=0)");

		final byte[] oid = oid(0x9C8FE559);

		byte[] data = new byte[] {
			0x00, 0x00, 0x00, 0x00
		};

		RctFrame request = new RctFrame((byte) 0x02, oid, data);

		var tx = this.getNewRctTransaction(request);
		
		// !!! DISABLED tx.executeWriteOnly();
		//tx.executeWriteOnly();

		this.periodicalsDisabledForCurrentConnection = true;
	}

	@Override
	public InetAddress getIpAddress() {
		return this.ipAddress;
	}

	public void setIpAddress(InetAddress ipAddress) {
		this.ipAddress = ipAddress;
	}
	
	private boolean disablePeriodicalSendings() {
		//return this.config.disablePeriodicalSendings();
		return true;
	}

}