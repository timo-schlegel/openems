package io.openems.edge.rctpower.bridge.api;

import static io.openems.edge.rctpower.bridge.api.ElementToChannelConverter.DIRECT_1_TO_1;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.rctpower.bridge.api.BridgeRctPower;
import io.openems.edge.rctpower.bridge.api.element.AbstractRctElement;
import io.openems.edge.rctpower.bridge.api.element.RctElement;
import io.openems.edge.rctpower.bridge.api.element.RctObjectElement;
import io.openems.edge.rctpower.bridge.api.task.ReadTask;
import io.openems.edge.rctpower.bridge.api.task.WriteTask;

public abstract class AbstractOpenemsRctComponent extends AbstractOpenemsComponent implements RctComponent {

	private final Logger log = LoggerFactory.getLogger(AbstractOpenemsRctComponent.class);

	private Integer slaveId;

	/*
	 * The protocol. Consume via 'getModbusProtocol()'
	 */
	private RctProtocol protocol = null;

	/**
	 * Default constructor for AbstractOpenemsModbusComponent.
	 *
	 * <p>
	 * Automatically initializes (i.e. creates {@link Channel} instances for each
	 * given ChannelId using the Channel-{@link Doc}.
	 *
	 * <p>
	 * It is important to list all Channel-ID enums of all inherited
	 * OpenEMS-Natures, i.e. for every OpenEMS Java interface you are implementing,
	 * you need to list the interface' ChannelID-enum here like
	 * Interface.ChannelId.values().
	 *
	 * <p>
	 * Use as follows:
	 *
	 * <pre>
	 * public YourPhantasticOpenemsComponent() {
	 * 	super(//
	 * 			OpenemsComponent.ChannelId.values(), //
	 * 			YourPhantasticOpenemsComponent.ChannelId.values());
	 * }
	 * </pre>
	 *
	 * <p>
	 * Note: the separation in firstInitialChannelIds and furtherInitialChannelIds
	 * is only there to enforce that calling the constructor cannot be forgotten.
	 * This way it needs to be called with at least one parameter - which is always
	 * at least "OpenemsComponent.ChannelId.values()". Just use it as if it was:
	 *
	 * <pre>
	 * AbstractOpenemsComponent(ChannelId[]... channelIds)
	 * </pre>
	 *
	 * @param firstInitialChannelIds   the Channel-IDs to initialize.
	 * @param furtherInitialChannelIds the Channel-IDs to initialize.
	 */
	protected AbstractOpenemsRctComponent(io.openems.edge.common.channel.ChannelId[] firstInitialChannelIds,
			io.openems.edge.common.channel.ChannelId[]... furtherInitialChannelIds) {
		super(firstInitialChannelIds, furtherInitialChannelIds);
	}

	protected void activate(String id) {
		throw new IllegalArgumentException("Use the other activate() method.");
	}

	/**
	 * Call this method from Component implementations activate().
	 *
	 * @param context         ComponentContext of this component. Receive it from
	 *                        parameter for @Activate
	 * @param id              ID of this component. Typically 'config.id()'
	 * @param alias           Human-readable name of this Component. Typically
	 *                        'config.alias()'. Defaults to 'id' if empty
	 * @param enabled         Whether the component should be enabled. Typically
	 *                        'config.enabled()'
	 * @param slaveId         Slave-ID of the Rctpower target (0 for Master)
	 * @param cm              An instance of ConfigurationAdmin. Receive it
	 *                        using @Reference
	 * @param rctpowerReference The name of the @Reference setter method for the
	 *                        Rctpower bridge - e.g. 'Rctpower' if you have a
	 *                        setRctpower()-method
	 * @param rctpowerId      The ID of the Rctpower bridge. Typically
	 *                        'config.rctpower_id()'
	 * @return true if the target filter was updated. You may use it to abort the
	 *         activate() method.
	 * @throws OpenemsException on error
	 */
	protected boolean activate(ComponentContext context, String id, String alias, boolean enabled,
			ConfigurationAdmin cm, String rctpowerReference, String rctpowerId) throws OpenemsException {
		super.activate(context, id, alias, enabled);
		return this.activateOrModified(0, cm, rctpowerReference, rctpowerId);
	}

	@Override
	protected void activate(ComponentContext context, String id, String alias, boolean enabled) {
		throw new IllegalArgumentException("Use the other activate() for Rctpower components!");
	}

	/**
	 * Call this method from Component implementations activate().
	 *
	 * @param context         ComponentContext of this component. Receive it from
	 *                        parameter for @Activate
	 * @param id              ID of this component. Typically 'config.id()'
	 * @param alias           Human-readable name of this Component. Typically
	 *                        'config.alias()'. Defaults to 'id' if empty
	 * @param enabled         Whether the component should be enabled. Typically
	 *                        'config.enabled()'
	 * @param slaveId         Slave-ID of the Rctpower target (0 for Master)
	 * @param cm              An instance of ConfigurationAdmin. Receive it
	 *                        using @Reference
	 * @param rctpowerReference The name of the @Reference setter method for the
	 *                        Rctpower bridge - e.g. 'Rctpower' if you have a
	 *                        setRctpower()-method
	 * @param rctpowerId      The ID of the Rctpower bridge. Typically
	 *                        'config.rctpower_id()'
	 * @return true if the target filter was updated. You may use it to abort the
	 *         modified() method.
	 * @throws OpenemsException on error
	 */
	protected boolean modified(ComponentContext context, String id, String alias, boolean enabled,
			ConfigurationAdmin cm, String rctpowerReference, String rctpowerId) throws OpenemsException {
		super.modified(context, id, alias, enabled);
		return this.activateOrModified(0, cm, rctpowerReference, rctpowerId);
	}

	@Override
	protected void modified(ComponentContext context, String id, String alias, boolean enabled) {
		throw new IllegalArgumentException("Use the other modified() for Rctpower components!");
	}

	/**
	 * Common tasks for @Activate and @Modified.
	 * 
	 * @param slaveId         Slave-ID of the Rctpower target (0 for Master)
	 * @param cm              An instance of ConfigurationAdmin. Receive it
	 *                        using @Reference
	 * @param rctpowerReference The name of the @Reference setter method for the
	 *                        Rctpower bridge - e.g. 'Rctpower' if you have a
	 *                        setRctpower()-method
	 * @param rctpowerId      The ID of the Rctpower bridge. Typically
	 *                        'config.rctpower_id()'
	 * @return true if the target filter was updated. You may use it to abort the
	 *         activate() or modified() method.
	 */
	private boolean activateOrModified(int slaveId, ConfigurationAdmin cm, String rctpowerReference, String rctpowerId) {
		System.out.println("AbstractOpenemsRctComponent activateOrModified");
		// update filter for 'Rctpower'
		if (OpenemsComponent.updateReferenceFilter(cm, this.servicePid(), rctpowerReference, rctpowerId)) {
			return true;
		}
		this.slaveId = slaveId;
		var bridge = this.bridge.get();
		if (this.isEnabled() && bridge != null) {
			System.out.println("   add protocol");
			bridge.addProtocol(this.id(), this.getRctProtocol());
			bridge.retryRctCommunication(this.id());
		}else System.out.println("   do not add protocol");
		if(bridge==null) System.out.println("      bridge is null");
		return false;
	}

	@Override
	protected void deactivate() {
		super.deactivate();
		var bridge = this.bridge.getAndSet(null);
		if (bridge != null) {
			bridge.removeProtocol(this.id());
		}
	}

	/**
	 * Gets the Rctpower Slave-ID.
	 *
	 * @return the Rctpower Slave-ID
	 */
	public Integer getSlaveId() {
		return this.slaveId;
	}

	private final AtomicReference<BridgeRctPower> bridge = new AtomicReference<>(null);

	/**
	 * Set the Rctpower bridge. Should be called by @Reference
	 *
	 * @param bridge the BridgeRctPower Reference
	 */
	protected void setRctpower(BridgeRctPower bridge) {
		this.bridge.set(bridge);
	}

	/**
	 * Unset the Rctpower bridge. Should be called by @Reference
	 *
	 * @param bridge the BridgeRctPower Reference
	 */
	protected void unsetRctpower(BridgeRctPower bridge) {
		this.bridge.compareAndSet(bridge, null);
		if (bridge != null) {
			bridge.removeProtocol(this.id());
		}
	}

	/**
	 * Gets the Rctpower-Bridge.
	 *
	 * @return the {@link BridgeRctPower}
	 */
	public BridgeRctPower getBridgeRct() {
		return this.bridge.get();
	}

	/**
	 * Gets the {@link RctProtocol}. Creates it via
	 * {@link #defineRctProtocol()} if it does not yet exist.
	 *
	 * @return the {@link RctProtocol}
	 * @throws OpenemsException on error
	 */
	protected RctProtocol getRctProtocol() {
		System.out.println("AbstractOpenemsRctComponent getRctProtocol()");
		var protocol = this.protocol;
		if (protocol != null) {
			return protocol;
		}
		this.protocol = this.defineRctProtocol();
		return this.protocol;
	}

	@Override
	public void retryRctCommunication() {
		var bridge = this.bridge.get();
		bridge.retryRctCommunication(this.id());
	}

	/**
	 * Defines the Rct protocol.
	 *
	 * @return the RctProtocol
	 * @throws OpenemsException on error
	 */
	protected abstract RctProtocol defineRctProtocol();

	/**
	 * Maps an Element to one or more RctChannels using converters, that convert
	 * the value forward and backwards.
	 */
	public class ChannelMapper<ELEMENT extends RctElement> {

		private final ELEMENT element;
		private final Map<Channel<?>, ElementToChannelConverter> channelMaps = new HashMap<>();

		public ChannelMapper(ELEMENT element) {
			this.element = element;
		}

		/**
		 * Maps the given element 1-to-1 to the Channel identified by channelId.
		 *
		 * @param channelId the Channel-ID
		 * @param converter the {@link ElementToChannelConverter}
		 * @return the element parameter
		 */
		public ChannelMapper<ELEMENT> m(io.openems.edge.common.channel.ChannelId channelId,
				ElementToChannelConverter converter) {
			return this.m(channelId, converter, new ChannelMetaInfo(this.element.startAddress));
		}

		/**
		 * Maps the given element 1-to-1 to the Channel identified by channelId.
		 *
		 * @param channelId       the Channel-ID
		 * @param converter       the {@link ElementToChannelConverter}
		 * @param channelMetaInfo an object that holds meta information about the
		 *                        Channel
		 * @return the element parameter
		 */
		public ChannelMapper<ELEMENT> m(io.openems.edge.common.channel.ChannelId channelId,
				ElementToChannelConverter converter, ChannelMetaInfo channelMetaInfo) {
			Channel<?> channel = AbstractOpenemsRctComponent.this.channel(channelId);
			channel.setMetaInfo(channelMetaInfo);
			this.channelMaps.put(channel, converter);
			return this;
		}

		/**
		 * Maps the given element to the Channel identified by channelId, applying the
		 * given @link{ElementToChannelConverter}.
		 *
		 * @param channelId        the Channel-ID
		 * @param elementToChannel the Element-To-Channel converter function for
		 *                         {@link ReadTask}s
		 * @param channelToElement the Channel-To-Channel converter function for
		 *                         {@link WriteTask}s
		 * @return the element parameter
		 */
		public ChannelMapper<ELEMENT> m(io.openems.edge.common.channel.ChannelId channelId,
				Function<Object, Object> elementToChannel, Function<Object, Object> channelToElement) {
			var converter = new ElementToChannelConverter(elementToChannel, channelToElement);
			return this.m(channelId, converter);
		}

		/**
		 * Builds the {@link ChannelMapper}.
		 *
		 * @return the {@link ChannelMapper}
		 */
		public ELEMENT build() {
			/*
			 * Forward Element Read-Value to Channel
			 */
			// This is guaranteed to work because of sealed abstract classes
			((AbstractRctElement<?, ?, ?>) this.element).onUpdateCallback(value -> { //
				/*
				 * Applies the updated value on every Channel in ChannelMaps using the given
				 * Converter. If the converter returns an Optional.empty, the value is ignored.
				 */
				this.channelMaps.forEach((channel, converter) -> {
					Object convertedValue;
					try {
						convertedValue = converter.elementToChannel(value);
					} catch (IllegalArgumentException e) {
						throw new IllegalArgumentException("Conversion for [" + channel.channelId() + "] failed", e);
					}
					channel.setNextValue(convertedValue);
				});
			});

			/*
			 * Forward Channel Write-Value to Element
			 */
			this.channelMaps.keySet().forEach(channel -> {
				if (channel instanceof WriteChannel<?> writeChannel) {
					writeChannel.onSetNextWrite(value -> {
						// dynamically get the Converter; this allows the converter to be changed
						var converter = this.channelMaps.get(channel);
						var convertedValue = converter.channelToElement(value);
						switch (this.element) {
						
						case RctObjectElement<?, ?> objectElement -> {
							try {
								objectElement.setNextWriteValueFromObject(convertedValue);
							} catch (IllegalArgumentException e) {
								AbstractOpenemsRctComponent.this.logWarn(AbstractOpenemsRctComponent.this.log,
										"Unable to write to RctObjectElement. " //
												+ "Address [" + this.element.startAddress + "] " //
												+ "Channel [" + channel.address() + "]. " //
												+ "Exception [" + e.getClass().getSimpleName() + "] " //
												+ ": " + e.getMessage());
								if (e instanceof IllegalArgumentException) {
									// This is likely a software development bug. Draw some attention:
									e.printStackTrace();
								}
							}
						}
						/*
						case CoilElement coilElement -> {
							try {
								coilElement.setNextWriteValue(TypeUtils.getAsType(OpenemsType.BOOLEAN, convertedValue));
							} catch (IllegalArgumentException e) {
								AbstractOpenemsRctComponent.this.logWarn(AbstractOpenemsRctComponent.this.log,
										"Unable to write to ModbusCoilElement " //
												+ "[" + this.element.startAddress + "]: " + e.getMessage());
							}
						}
						*/

						default //
							-> AbstractOpenemsRctComponent.this.logWarn(AbstractOpenemsRctComponent.this.log,
									"Unable to write to Element " //
											+ "[" + this.element.startAddress + "]: it is not a RctpowerElement");
						}
					});
				}
			});

			return this.element;
		}
	}

	/**
	 * Creates a ChannelMapper that can be used with builder pattern inside the
	 * protocol definition.
	 *
	 * @param <T>     the type of the {@link RctElement}
	 * @param element the RctElement
	 * @return a {@link ChannelMapper}
	 */
	protected final <T extends RctElement> ChannelMapper<T> m(T element) {
		return new ChannelMapper<>(element);
	}

	/**
	 * Maps the given BitsWordElement.
	 *
	 * @param bitsWordElement the RctElement
	 * @return the element parameter
	 */
	//protected final BitsWordElement m(BitsWordElement bitsWordElement) {
	//	return bitsWordElement;
	//}

	/**
	 * Maps the given element 1-to-1 to the Channel identified by channelId.
	 *
	 * @param <T>       the type of the {@link RctElement}
	 * @param channelId the Channel-ID
	 * @param element   the RctElement
	 * @return the element parameter
	 */
	protected final <T extends RctElement> T m(io.openems.edge.common.channel.ChannelId channelId, T element) {
		return this.m(channelId, element, DIRECT_1_TO_1);
	}

	/**
	 * Maps the given element 1-to-1 to the Channel identified by channelId.
	 *
	 * @param <T>             the type of the {@link RctElement}
	 * @param channelId       the Channel-ID
	 * @param element         the RctElement
	 * @param channelMetaInfo an object that holds meta information about the
	 *                        Channel
	 * @return the element parameter
	 */
	protected final <T extends RctElement> T m(io.openems.edge.common.channel.ChannelId channelId, T element,
			ChannelMetaInfo channelMetaInfo) {
		return this.m(channelId, element, DIRECT_1_TO_1, channelMetaInfo);
	}

	/**
	 * Maps the given element to the Channel identified by channelId, applying the
	 * given @link{ElementToChannelConverter}.
	 *
	 * @param <T>       the type of the {@link ModbusElement}
	 * @param channelId the Channel-ID
	 * @param element   the ModbusElement
	 * @param converter the ElementToChannelConverter
	 * @return the element parameter
	 */
	protected final <T extends RctElement> T m(io.openems.edge.common.channel.ChannelId channelId, T element,
			ElementToChannelConverter converter) {
		return new ChannelMapper<>(element) //
				.m(channelId, converter) //
				.build();
	}

	/**
	 * Maps the given element to the Channel identified by channelId, applying the
	 * given @link{ElementToChannelConverter}.
	 *
	 * @param <T>             the type of the {@link ModbusElement}
	 * @param channelId       the Channel-ID
	 * @param element         the ModbusElement
	 * @param converter       the ElementToChannelConverter
	 * @param channelMetaInfo an object that holds meta information about the
	 *                        Channel
	 * @return the element parameter
	 */
	protected final <T extends RctElement> T m(io.openems.edge.common.channel.ChannelId channelId, T element,
			ElementToChannelConverter converter, ChannelMetaInfo channelMetaInfo) {
		return new ChannelMapper<>(element) //
				.m(channelId, converter, channelMetaInfo) //
				.build();
	}

	public enum BitConverter {
		DIRECT_1_TO_1, INVERT
	}

}