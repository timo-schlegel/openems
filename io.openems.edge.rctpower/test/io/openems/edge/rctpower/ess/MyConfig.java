package io.openems.edge.rctpower.ess;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.common.utils.ConfigUtils;
import io.openems.edge.rctpower.enums.ControlMode;
import io.openems.edge.rctpower.ess.MyConfig.Builder;
import io.openems.edge.rctpower.ess.Config;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id;
		private boolean pvExportLimit;
		private ControlMode controlMode;
		private String rctpowerId;
		private int capacity;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}
		
		public Builder setPvExportLimit(boolean pvExportLimit) {
			this.pvExportLimit = pvExportLimit;
			return this;
		}	
		
		public Builder setControlMode(ControlMode controlMode) {
			this.controlMode = controlMode;
			return this;
		}

		public Builder setRctPowerId(String rctpowerId) {
			this.rctpowerId = rctpowerId;
			return this;
		}

		public MyConfig build() {
			return new MyConfig(this);
		}
	}

	/**
	 * Create a Config builder.
	 * 
	 * @return a {@link Builder}
	 */
	public static Builder create() {
		return new Builder();
	}

	private final Builder builder;

	private MyConfig(Builder builder) {
		super(Config.class, builder.id);
		this.builder = builder;
	}

	@Override
	public boolean pvExportLimit() {
		return this.builder.pvExportLimit;
	}
	
	@Override
	public ControlMode controlMode() {
		return this.builder.controlMode;
	}	
	
	@Override
	public String rctpower_id() {
		return this.builder.rctpowerId;
	}
	
	@Override
	public int capacity() {
		return this.builder.capacity;
	}	

	@Override
	public String Rctpower_target() {
		return ConfigUtils.generateReferenceTargetFilter(this.id(), this.rctpower_id());
	}

}