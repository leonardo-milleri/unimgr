package org.opendaylight.unimgr.utils;

import org.opendaylight.unimgr.mef.nrp.api.ActivationDriver;
import org.opendaylight.unimgr.mef.nrp.api.ActivationDriverBuilder;
import org.opendaylight.yang.gen.v1.uri.onf.coremodel.corenetworkmodule.objectclasses.rev160413.GFcPort;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * @author bartosz.michalik@amartus.com
 */
public class ActivationDriverMocks {
    /**
     * Prepare mock {@link ActivationDriverBuilder}. The driver is produced via provided producer function. This covers
     * single port requests.
     * @param producer to build driver
     * @return driver builder mock
     */
    public static ActivationDriverBuilder prepareDriver(Function<GFcPort, ActivationDriver> producer) {
        final ActivationDriverBuilder mock = mock(ActivationDriverBuilder.class);

        doAnswer(inv -> {
            GFcPort port = (GFcPort) inv.getArguments()[0];
            return Optional.ofNullable(producer.apply(port));
        }).when(mock).driverFor(any(GFcPort.class), any(ActivationDriverBuilder.BuilderContext.class));

        doReturn(Optional.empty()).when(mock)
                .driverFor(any(GFcPort.class), any(GFcPort.class), any(ActivationDriverBuilder.BuilderContext.class));

        return mock;
    }

    /**
     * Prepare mock {@link ActivationDriverBuilder}. The driver is produced via provided producer function.  This covers
     * dual port requests (for internal cross-connect).
     * @param producer to build driver
     * @return driver builder mock
     */
    public static ActivationDriverBuilder prepareDriver(BiFunction<GFcPort, GFcPort, ActivationDriver> producer) {
        final ActivationDriverBuilder mock = mock(ActivationDriverBuilder.class);

        doAnswer(inv -> {
            GFcPort port1 = (GFcPort) inv.getArguments()[0];
            GFcPort port2 = (GFcPort) inv.getArguments()[1];
            return Optional.ofNullable(producer.apply(port1, port2));
        }).when(mock).driverFor(any(GFcPort.class), any(GFcPort.class), any(ActivationDriverBuilder.BuilderContext.class));

        doReturn(Optional.empty()).when(mock)
                .driverFor(any(GFcPort.class), any(ActivationDriverBuilder.BuilderContext.class));
        return mock;
    }
}
