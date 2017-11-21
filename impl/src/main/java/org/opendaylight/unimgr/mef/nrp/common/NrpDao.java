/*
 * Copyright (c) 2017 Cisco Systems Inc and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.unimgr.mef.nrp.common;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.opendaylight.controller.md.sal.binding.api.ReadOnlyTransaction;
import org.opendaylight.controller.md.sal.binding.api.ReadTransaction;
import org.opendaylight.controller.md.sal.binding.api.ReadWriteTransaction;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.ReadFailedException;
import org.opendaylight.unimgr.mef.nrp.api.TapiConstants;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.Context;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.Uuid;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.context.attrs.ServiceInterfacePoint;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.context.attrs.ServiceInterfacePointKey;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.connectivity.context.Connection;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.connectivity.context.ConnectionKey;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.connectivity.context.ConnectivityService;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.connectivity.context.ConnectivityServiceKey;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.Context1;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.topology.Link;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.get.node.edge.point.details.output.NodeEdgePoint;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.node.OwnedNodeEdgePoint;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.node.OwnedNodeEdgePointBuilder;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.node.OwnedNodeEdgePointKey;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.topology.LinkKey;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.topology.context.Topology;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.topology.context.TopologyKey;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.topology.Node;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.topology.NodeBuilder;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.topology.NodeKey;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.binding.KeyedInstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;

/**
 * @author bartosz.michalik@amartus.com
 */
public class NrpDao  {
    private static final Logger LOG = LoggerFactory.getLogger(NrpDao.class);
    private final ReadWriteTransaction tx;
    private final ReadTransaction rtx;


    public NrpDao(ReadWriteTransaction tx) {
        this.tx = tx;
        this.rtx = tx;
    }
    public NrpDao(ReadOnlyTransaction tx) {
        this.rtx = tx;
        this.tx =  null;
    }

    private Function<NodeEdgePoint, OwnedNodeEdgePoint> toNep = nep -> new OwnedNodeEdgePointBuilder(nep).build();

    public Node createSystemNode(String nodeId, List<OwnedNodeEdgePoint> neps, Uuid encapTopology) {
        verifyTx();
        Uuid uuid = new Uuid(nodeId);
        Node node = new NodeBuilder()
                .setKey(new NodeKey(uuid))
                .setUuid(uuid)
                .setOwnedNodeEdgePoint(neps)
                .setEncapTopology(encapTopology)
                .build();
        tx.put(LogicalDatastoreType.OPERATIONAL, systemNode(nodeId), node);
        return node;
    }

    public Node createSystemNode(String nodeId, List<OwnedNodeEdgePoint> neps) {
        return createSystemNode(nodeId, neps, null);
    }

    public Node updateInternalNode(String topoId, String nodeId, List<OwnedNodeEdgePoint> neps) {
        verifyTx();
        Uuid uuid = new Uuid(nodeId);
        Node node = new NodeBuilder()
                .setKey(new NodeKey(uuid))
                .setUuid(uuid)
                .setOwnedNodeEdgePoint(neps)
                .build();
        tx.put(LogicalDatastoreType.OPERATIONAL, internalNode(topoId, nodeId), node, true);
        return node;
    }

    public Node deleteInternalNode(String topoId, String nodeId) {
        verifyTx();
        Uuid uuid = new Uuid(nodeId);
        Node node = new NodeBuilder()
                .setKey(new NodeKey(uuid))
                .setUuid(uuid)
                .build();
        tx.delete(LogicalDatastoreType.OPERATIONAL, internalNode(topoId, nodeId));
        return node;
    }

    public Link updateLink(String topoId, Link link)
    {
        verifyTx();

        tx.put(LogicalDatastoreType.OPERATIONAL,topo(topoId).child(Link.class, link.getKey()), link, true);
        return link;
    }

    public Link deleteLink(String topoId, Link link)
    {
        verifyTx();

        tx.delete(LogicalDatastoreType.OPERATIONAL,topo(topoId).child(Link.class, link.getKey()));
        return link;
    }

    private void verifyTx() {
        if (tx == null) {
            throw new IllegalStateException("Top perform write operation read write transaction is needed");
        }
    }

    /**
     * Update nep or add if it does not exist.
     * @param nodeId node id
     * @param nep nep to update
     */
    public void updateNep(String nodeId, OwnedNodeEdgePoint nep) {
        updateNep(new Uuid(nodeId), nep);
    }

    public void updateNep(Uuid nodeId, OwnedNodeEdgePoint nep) {
        InstanceIdentifier<OwnedNodeEdgePoint> nodeIdent = systemNode(nodeId).child(OwnedNodeEdgePoint.class, new OwnedNodeEdgePointKey(nep.getUuid()));
        tx.put(LogicalDatastoreType.OPERATIONAL, nodeIdent, nep);
    }

    public void removeNep(String nodeId, String nepId, boolean removeSips) {
        verifyTx();
        InstanceIdentifier<OwnedNodeEdgePoint> nepIdent = systemNode(nodeId).child(OwnedNodeEdgePoint.class, new OwnedNodeEdgePointKey(new Uuid(nepId)));
        try {
            Optional<OwnedNodeEdgePoint> opt = tx.read(LogicalDatastoreType.OPERATIONAL, nepIdent).checkedGet();
            if (opt.isPresent()) {
                tx.delete(LogicalDatastoreType.OPERATIONAL,nepIdent);
                if (removeSips) {
                    List<Uuid> sips = opt.get().getMappedServiceInterfacePoint();
                    removeSips(sips == null ? null : sips.stream());
                }
            }
        } catch (ReadFailedException e) {
            LOG.error("Cannot read {} with id {}",OwnedNodeEdgePoint.class, nodeId);
        }
    }

    public void addSip(ServiceInterfacePoint sip) {
        verifyTx();
        tx.put(LogicalDatastoreType.OPERATIONAL,
            ctx().child(ServiceInterfacePoint.class, new ServiceInterfacePointKey(sip.getUuid())),
                sip);
    }

    public OwnedNodeEdgePoint readNep(String nodeId, String nepId) throws ReadFailedException {
        KeyedInstanceIdentifier<OwnedNodeEdgePoint, OwnedNodeEdgePointKey> nepKey = systemNode(nodeId).child(OwnedNodeEdgePoint.class, new OwnedNodeEdgePointKey(new Uuid(nepId)));
        return rtx.read(LogicalDatastoreType.OPERATIONAL, nepKey).checkedGet().orNull();
    }

    public boolean hasSip(String nepId) {
        Uuid universalId = new Uuid("sip:" + nepId);
        try {
            return rtx.read(LogicalDatastoreType.OPERATIONAL,
                    ctx().child(ServiceInterfacePoint.class, new ServiceInterfacePointKey(universalId))).checkedGet().isPresent();
        } catch (ReadFailedException e) {
            LOG.error("Cannot read sip with id {}", universalId.getValue());
        }
        return false;
    }

    public boolean hasNep(String nodeId, String nepId) throws ReadFailedException {
        return readNep(nodeId, nepId) != null;
    }

    public Topology getTopology(String uuid) throws ReadFailedException {
        Optional<Topology> topology = rtx.read(LogicalDatastoreType.OPERATIONAL, topo(uuid)).checkedGet();
        return topology.orNull();
    }

    public static InstanceIdentifier<Context> ctx() {
        return InstanceIdentifier.create(Context.class);
    }

    public static InstanceIdentifier<Topology> topo(String topoId) {
        return ctx()
                .augmentation(Context1.class)
                .child(Topology.class, new TopologyKey(new Uuid(topoId)));
    }

    public static InstanceIdentifier<Node> systemNode(String nodeId) {
        return systemNode(new Uuid(nodeId));
    }

    public static InstanceIdentifier<Node> systemNode(Uuid nodeId) {
        return topo(TapiConstants.PRESTO_SYSTEM_TOPO).child(Node.class, new NodeKey(nodeId));
    }

    public static InstanceIdentifier<Node> internalNode(String topoId, String nodeId) {
        return topo(topoId).child(Node.class, new NodeKey(new Uuid(nodeId)));
    }

    public static InstanceIdentifier<Node> abstractNode() {
        return topo(TapiConstants.PRESTO_EXT_TOPO).child(Node.class, new NodeKey(new Uuid(TapiConstants.PRESTO_ABSTRACT_NODE)));
    }


    public void removeSips(Stream<Uuid>  uuids) {
        verifyTx();
        if (uuids == null) {
            return;
        }
        uuids.forEach(sip -> {
            LOG.debug("removing ServiceInterfacePoint with id {}", sip);
            tx.delete(LogicalDatastoreType.OPERATIONAL, ctx().child(ServiceInterfacePoint.class, new ServiceInterfacePointKey(sip)));
        });
    }

    public void removeNode(String nodeId, boolean removeSips) {
        verifyTx();
        if (removeSips) {
            try {
                Optional<Node> opt = tx.read(LogicalDatastoreType.OPERATIONAL, systemNode(nodeId)).checkedGet();
                if (opt.isPresent()) {
                    removeSips(opt.get().getOwnedNodeEdgePoint().stream().flatMap(nep -> nep.getMappedServiceInterfacePoint() == null
                                                                                  ? Stream.empty()
                                                                                  : nep.getMappedServiceInterfacePoint().stream()
                    ));
                }
            } catch (ReadFailedException e) {
                LOG.error("Cannot read node with id {}", nodeId);
            }
        }

        tx.delete(LogicalDatastoreType.OPERATIONAL, systemNode(nodeId));
    }

    public void updateAbstractNep(OwnedNodeEdgePoint nep) {
        verifyTx();
        InstanceIdentifier<OwnedNodeEdgePoint> nodeIdent = abstractNode().child(OwnedNodeEdgePoint.class, new OwnedNodeEdgePointKey(nep.getUuid()));
        tx.merge(LogicalDatastoreType.OPERATIONAL, nodeIdent, nep);
    }

    public void deleteAbstractNep(OwnedNodeEdgePoint nep) {
        verifyTx();
        InstanceIdentifier<OwnedNodeEdgePoint> nodeIdent = abstractNode().child(OwnedNodeEdgePoint.class, new OwnedNodeEdgePointKey(nep.getUuid()));
        tx.delete(LogicalDatastoreType.OPERATIONAL, nodeIdent);
    }

    public List<ConnectivityService> getConnectivityServiceList() {
        try {
            return rtx.read(LogicalDatastoreType.OPERATIONAL,
                    ctx().augmentation(org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.Context1.class))
                    .checkedGet().orNull().getConnectivityService();
        } catch (ReadFailedException e) {
            LOG.warn("reading connectivity services failed", e);
            return null;
        }
    }

    public ConnectivityService getConnectivityService(Uuid id) {
        try {
            return rtx.read(LogicalDatastoreType.OPERATIONAL, ctx().augmentation(org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.Context1.class).child(ConnectivityService.class, new ConnectivityServiceKey(id)))
                    .checkedGet().orNull();

        } catch (ReadFailedException e) {
            LOG.warn("reading connectivity service failed", e);
            return null;
        }
    }

    public ServiceInterfacePoint getSip(String sipId) throws ReadFailedException {
        KeyedInstanceIdentifier<ServiceInterfacePoint, ServiceInterfacePointKey> key = ctx().child(ServiceInterfacePoint.class, new ServiceInterfacePointKey(new Uuid(sipId)));
        return rtx.read(LogicalDatastoreType.OPERATIONAL, key).checkedGet().orNull();
    }

    public ConnectivityService getConnectivityService(String id) {
        return getConnectivityService(new Uuid(id));
    }

    public Connection getConnection(Uuid connectionId) {
        try {
            return rtx.read(LogicalDatastoreType.OPERATIONAL, ctx().augmentation(org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.connectivity.rev170712.Context1.class).child(Connection.class, new ConnectionKey(connectionId)))
                    .checkedGet().orNull();

        } catch (ReadFailedException e) {
            LOG.warn("reading connectivity service failed", e);
            return null;
        }
    }
}
