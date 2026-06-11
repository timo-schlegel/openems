package io.openems.edge.rctpower.bridge.api;

import java.net.InetAddress;
import java.util.List;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.common.channel.Debounce;
import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.LongReadChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.rctpower.bridge.api.LogVerbosity;
import io.openems.edge.rctpower.bridge.api.RctFrame;
import io.openems.edge.rctpower.bridge.api.RctProtocol;
import io.openems.edge.rctpower.bridge.api.RctTransaction;

@ProviderType
public interface BridgeRctPower extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		CYCLE_TIME_IS_TOO_SHORT(Doc.of(Level.INFO) //
				.debounce(10, Debounce.TRUE_VALUES_IN_A_ROW_TO_SET_TRUE)), //
		/**
		 * Delay per Cycle before starting to execute Rct Tasks. Global Cycle-Time
		 * can be reduced by this amount, without causing CYCLE_TIME_IS_TOO_SHORT.
		 */
		CYCLE_DELAY(Doc.of(OpenemsType.LONG) //
				.unit(Unit.MILLISECONDS)); //

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	public RctTransaction getNewRctTransaction(RctFrame frame) throws OpenemsException;
	public RctBatchTransaction getNewRctBatchTransaction(List<RctRequest> rctRequests) throws OpenemsException;
	public void closeRctConnection();
	
	/**
	 * Gets the IP address.
	 *
	 * @return the IP address
	 */
	public InetAddress getIpAddress();
	
	/**
	 * Gets the configured {@link LogVerbosity}.
	 * 
	 * @return {@link LogVerbosity}
	 */
	public LogVerbosity getLogVerbosity();	
	
	/**
	 * Gets the configured max number of errors before an element should be
	 * invalidated?.
	 *
	 * @return value
	 */
	public int invalidateElementsAfterReadErrors();	
	
	/**
	 * Gets the Channel for {@link ChannelId#CYCLE_TIME_IS_TOO_SHORT}.
	 *
	 * @return the Channel
	 */
	public default StateChannel getCycleTimeIsTooShortChannel() {
		return this.channel(ChannelId.CYCLE_TIME_IS_TOO_SHORT);
	}

	/**
	 * Gets the Cycle-Time-is-too-short State. See
	 * {@link ChannelId#CYCLE_TIME_IS_TOO_SHORT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Boolean> getCycleTimeIsTooShort() {
		return this.getCycleTimeIsTooShortChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#CYCLE_TIME_IS_TOO_SHORT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setCycleTimeIsTooShort(boolean value) {
		this.getCycleTimeIsTooShortChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#CYCLE_DELAY}.
	 *
	 * @return the Channel
	 */
	public default LongReadChannel getCycleDelayChannel() {
		return this.channel(ChannelId.CYCLE_DELAY);
	}

	/**
	 * Gets the Cycle Delay in [ms], see {@link ChannelId#CYCLE_DELAY}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Long> getCycleDelay() {
		return this.getCycleDelayChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#CYCLE_DELAY}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setCycleDelay(long value) {
		this.getCycleDelayChannel().setNextValue(value);
	}

	/**
	 * Adds a Protocol with a source identifier to this RctPower Bridge.
	 *
	 * @param sourceId the unique source identifier
	 * @param protocol the RctPower Protocol
	 */
	public void addProtocol(String sourceId, RctProtocol protocol);

	/**
	 * Removes a Protocol from this RctPower Bridge.
	 *
	 * @param sourceId the unique source identifier
	 */
	public void removeProtocol(String sourceId);

	/**
	 * The Rct Bridge marks defective Components, e.g. if there are communication
	 * failures. If a component is marked as defective, reads and writes are paused
	 * for an increasing waiting time. This method resets the waiting time, causing
	 * the Rct Bridge to retry if a Component is not anymore defective.
	 * 
	 * <p>
	 * Use this method if there is good reason that a RctPower Component should be
	 * available again 'now', e.g. because it was turned on manually.
	 * 
	 * @param sourceId the unique source identifier
	 */
	public void retryRctCommunication(String sourceId);
}