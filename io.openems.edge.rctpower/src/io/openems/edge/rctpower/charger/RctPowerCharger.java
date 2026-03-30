package io.openems.edge.rctpower.charger;

import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.dccharger.api.EssDcCharger;
import io.openems.edge.rctpower.bridge.api.RctComponent;

public interface RctPowerCharger extends EssDcCharger, RctComponent, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
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
}
