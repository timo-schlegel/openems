package io.openems.edge.rctpower.ess;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.function.ThrowingConsumer;
import io.openems.edge.common.channel.FloatReadChannel;
import io.openems.edge.common.channel.FloatWriteChannel;

public class SetPvExportLimitHandler implements ThrowingConsumer<Optional<Integer>, OpenemsNamedException> {

	private static final float COMPARE_THRESHOLD = 0.0001F;

	private final RctPowerEss parent;

	public SetPvExportLimitHandler(RctPowerEss parent) {
		this.parent = parent;
	}

	/**
	 * Handles a PV-Inverter Export power limitation request.
	 * 
	 * @param activeExportPowerLimitOpt an Optional export power limit; empty value sets the
	 *                            		allowed plan peak power to 100 %, i.e. no limit
	 * @throws OpenemsNamedException on error
	 */
	@Override
	public void accept(Optional<Integer> activeExportPowerLimitOpt) throws OpenemsNamedException {
		FloatWriteChannel wMaxLimPctChannel;
		FloatReadChannel wRtgChannel;

		// Get Export Power Limitation Channel
		wMaxLimPctChannel = this.parent.channel(RctPowerEss.ChannelId.POWER_REDUCTION);

		// Get Solar Plant Peak Power Channel
		wRtgChannel = this.parent.channel(RctPowerEss.ChannelId.POWER_REDUCTION_MAX_SOLAR);


		float wMaxLimPct;
		if (activeExportPowerLimitOpt.isPresent()) {
			/*
			 * A ActiveExportPowerLimit is set
			 */
			int activeExportPowerLimit = activeExportPowerLimitOpt.get();

			// calculate limitation in percent
			float wRtg = wRtgChannel.value().getOrError();
			wMaxLimPct = round(activeExportPowerLimit / wRtg, 2);
		} else {
			/*
			 * No ActiveExportPowerLimit is set -> reset to 100 %
			 */
			wMaxLimPct = 1F;
		}

		// keep percentage in range [0, 1].
		if (wMaxLimPct > 1F) {
			wMaxLimPct = 1F;
		} else if (wMaxLimPct < 0) {
			wMaxLimPct = 0F;
		}
		
		if (
		// Value is not available
		!wMaxLimPctChannel.value().isDefined()
				// Value changed
				|| Math.abs(wMaxLimPct - wMaxLimPctChannel.value().get()) > COMPARE_THRESHOLD) {

			// Set Export Power Limitation
			wMaxLimPctChannel.setNextWriteValue(wMaxLimPct);
		}

	}
	
	private static float round(float value, int places) {
	    BigDecimal bd = new BigDecimal(Double.toString(value));
	    bd = bd.setScale(places, RoundingMode.HALF_UP);
	    return bd.floatValue();
	}	
}