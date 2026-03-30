package io.openems.edge.rctpower.bridge.api;

import java.util.stream.Stream;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.rctpower.bridge.api.worker.RctWorker;

/**
 * Abstract service for connecting to, querying and writing to a RctPower device.
 */
public abstract class AbstractRctPowerBridge extends AbstractOpenemsComponent implements BridgeRctPower, EventHandler {

	/**
	 * Default timeout in [ms].
	 */
	public static final int DEFAULT_TIMEOUT = 1000;

	/**
	 * Default retries.
	 */
	public static final int DEFAULT_RETRIES = 1;
	
	public static final int DEFAULT_PORT = 8899;

	/**
	 * Wait after write  in [ms].
	 */
	public static final int WAIT_AFTER_WRITE = 20;
	
	private Config config = null;

	protected final RctWorker worker = new RctWorker(
			// Execute Task
			task -> task.execute(this),
			// Invalidate RctElements
			elements -> Stream.of(elements).forEach(e -> e.invalidate(this)),
			// Set ChannelId.CYCLE_TIME_IS_TOO_SHORT
			state -> this._setCycleTimeIsTooShort(state),
			// Set ChannelId.CYCLE_DELAY
			cycleDelay -> this._setCycleDelay(cycleDelay),
			// LogHandler
			() -> this.config.log //
	);

	protected AbstractRctPowerBridge(io.openems.edge.common.channel.ChannelId[] firstInitialChannelIds,
			io.openems.edge.common.channel.ChannelId[]... furtherInitialChannelIds) {
		super(firstInitialChannelIds, furtherInitialChannelIds);
	}

	@Override
	@Deprecated
	protected void activate(ComponentContext context, String id, String alias, boolean enabled) {
		throw new IllegalArgumentException("Use the other activate() method.");
	}

	protected void activate(ComponentContext context, Config config) {
		super.activate(context, config.id, config.alias, config.enabled);
		this.applyConfig(config);
		if (config.enabled) {
			this.worker.activate(config.id);
		}
	}

	@Override
	protected void deactivate() {
		super.deactivate();
		this.worker.deactivate();
		this.closeRctConnection();
	}

	@Override
	@Deprecated
	protected void modified(ComponentContext context, String id, String alias, boolean enabled) {
		throw new IllegalArgumentException("Use the other modified() method.");
	}

	protected void modified(ComponentContext context, Config config) {
		super.modified(context, config.id, config.alias, config.enabled);
		this.applyConfig(config);
		if (config.enabled) {
			this.worker.modified(config.id);
		} else {
			this.worker.deactivate();
		}
	}

	private void applyConfig(Config config) {
		this.config = config;
	}

	/**
	 * Adds the protocol.
	 *
	 * @param sourceId Component-ID of the source
	 * @param protocol the RctProtocol
	 */
	@Override
	public void addProtocol(String sourceId, RctProtocol protocol) {
		System.out.println("Add Protocol to AbstractRctPowerBridge");
		this.worker.addProtocol(sourceId, protocol);
		this.retryRctCommunication(sourceId);
	}

	/**
	 * Removes the protocol.
	 *
	 * @param sourceId Component-ID of the source
	 */
	@Override
	public void removeProtocol(String sourceId) {
		this.worker.removeProtocol(sourceId);
	}

	@Override
	public void handleEvent(Event event) {
		if (this.config == null || !this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE //
			-> this.worker.onBeforeProcessImage();

		case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE //
			-> this.worker.onExecuteWrite();
		}
	}

	@Override
	public String debugLog() {
		if (this.config == null) {
			return null;
		}
		return switch (this.config.log.verbosity) {
		case NONE -> //
			null;
		case DEBUG_LOG, READS_AND_WRITES, READS_AND_WRITES_DURATION, READS_AND_WRITES_VERBOSE,
				READS_AND_WRITES_DURATION_TRACE_EVENTS -> //
			"CycleDelay:" + this.getCycleDelay().asString(); //
		};
	}

	/**
	 * Creates a new Rct Transaction on an open Rct connection.
	 *
	 * @param RctFrame for request
	 * @return the Rct Transaction
	 * @throws OpenemsException on error
	 */
	public abstract RctTransaction getNewRctTransaction(RctFrame frame) throws OpenemsException;

	/**
	 * Closes the Rct connection.
	 */
	public abstract void closeRctConnection();

	/**
	 * Gets the configured {@link LogVerbosity}.
	 * 
	 * @return {@link LogVerbosity}
	 */
	public LogVerbosity getLogVerbosity() {
		return this.config.log.verbosity;
	}

	/**
	 * Gets the configured max number of errors before an element should be
	 * invalidated?.
	 *
	 * @return value
	 */
	public int invalidateElementsAfterReadErrors() {
		return this.config.invalidateElementsAfterReadErrors;
	}

	@Override
	public void retryRctCommunication(String sourceId) {
		this.worker.retryRctCommunication(sourceId);
	}
}