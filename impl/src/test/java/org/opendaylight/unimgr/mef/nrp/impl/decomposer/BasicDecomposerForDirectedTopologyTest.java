/*
 * Copyright (c) 2017 Cisco Systems Inc and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.unimgr.mef.nrp.impl.decomposer;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.opendaylight.controller.md.sal.binding.api.ReadWriteTransaction;
import org.opendaylight.unimgr.mef.nrp.api.FailureResult;
import org.opendaylight.unimgr.mef.nrp.api.Subrequrest;
import org.opendaylight.unimgr.mef.nrp.impl.AbstractTestWithTopo;
import org.opendaylight.unimgr.mef.nrp.impl.NrpInitializer;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.ForwardingDirection;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.OperationalState;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.PortDirection;
import org.opendaylight.yangtools.yang.common.OperationFailedException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * @author bartosz.michalik@amartus.com
 */
public class BasicDecomposerForDirectedTopologyTest extends AbstractTestWithTopo {

    private BasicDecomposer decomposer;

    @Before
    public void setUp() throws Exception {
        dataBroker = getDataBroker();
        new NrpInitializer(dataBroker).init();
        decomposer = new BasicDecomposer(dataBroker);

    }

    @Rule
    public ExpectedException expected = ExpectedException.none();

    @Test
    public void twoNodesTestDirection() throws FailureResult, OperationFailedException {
        //having three nodes, but only two nodes connected, with directional links and ports
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        n(tx, true, "n1", Stream.of(pI("n1:1"), pO("n1:2")));
        n(tx, true, "n2", Stream.of(pO("n2:1"), pI("n2:2")));
        n(tx, true, "n3", Stream.of(pI("n3:1")));
        l(tx, "n1", "n1:1", "n2", "n2:1", OperationalState.Enabled, ForwardingDirection.Bidirectional);
        tx.submit().checkedGet();
        //when
        List<Subrequrest> decomposed = decomposer.decompose(Arrays.asList(ep("n1:2"), ep("n2:2")), null);
        assertNotNull(decomposed);
        assertEquals(2, decomposed.size());
    }

    @Test
    public void threeNodesTestAll() throws FailureResult, OperationFailedException {
        //having three nodes, but only two nodes connected, with directional links and ports
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        threeNodesTopo(tx);
        tx.submit().checkedGet();
        //when
        List<Subrequrest> decomposed = decomposer.decompose(Arrays.asList(ep("n1:2"), ep("n3:2")), null);
        assertNotNull(decomposed);
        assertEquals(3, decomposed.size());
    }


    @Test
    public void threeNodesTestNone() throws FailureResult, OperationFailedException {
        //having three nodes, but only two nodes connected, with directional links and ports
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        threeNodesTopo(tx);
        tx.submit().checkedGet();
        //when
        List<Subrequrest> decomposed = decomposer.decompose(Arrays.asList(ep("n1:1"), ep("n1:2")), null);
        assertNull(decomposed);

    }

    private  void threeNodesTopo(ReadWriteTransaction tx) {
        n(tx, true, "n1", Stream.of(pI("n1:1"), pI("n1:2"), pO("n1:3")));
        n(tx, true, "n2", Stream.of(pI("n2:1"), pO("n2:2")));
        n(tx, true, "n3", Stream.of(pI("n3:1"), pO("n3:2"), pO("n3:3")));
        l(tx, "n1", "n1:3", "n2", "n2:1", OperationalState.Enabled, ForwardingDirection.Bidirectional);
        l(tx, "n2", "n2:2", "n3", "n3:1", OperationalState.Enabled, ForwardingDirection.Bidirectional);
    }

    AbstractTestWithTopo.Pair pO(String id) {
        return new Pair(id, PortDirection.Output);
    }

    AbstractTestWithTopo.Pair pI(String id) {
        return new Pair(id, PortDirection.Input);
    }

    AbstractTestWithTopo.Pair pB(String id) {
        return new Pair(id, PortDirection.Bidirectional);
    }


}
