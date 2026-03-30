package io.openems.edge.rctpower.bridge;

import org.junit.Test;

import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.rctpower.bridge.BridgeRctPowerImpl;
import io.openems.edge.common.test.ComponentTest;

public class MyDeviceTest {

	@Test
	public void test() throws Exception {
		new ComponentTest(new BridgeRctPowerImpl()) //
				.activate(MyConfig.create() //
						.setId("component0") //
						.build()) //
				.next(new TestCase()) //
				.deactivate();
	}

}
