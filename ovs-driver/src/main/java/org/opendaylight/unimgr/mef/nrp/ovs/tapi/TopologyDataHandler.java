/*
 * Copyright (c) 2017 Cisco Systems Inc and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.unimgr.mef.nrp.ovs.tapi;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import org.opendaylight.controller.md.sal.binding.api.*;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.unimgr.mef.nrp.common.NrpDao;
import org.opendaylight.unimgr.mef.nrp.common.ResourceNotAvailableException;
import org.opendaylight.unimgr.mef.nrp.ovs.transaction.TopologyTransaction;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Uri;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.LifecycleState;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.Uuid;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.context.attrs.ServiceInterfacePoint;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.context.attrs.ServiceInterfacePointBuilder;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.context.attrs.ServiceInterfacePointKey;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.service._interface.point.StateBuilder;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.node.OwnedNodeEdgePoint;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.node.OwnedNodeEdgePointBuilder;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.node.OwnedNodeEdgePointKey;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.topology.LinkBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyKey;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Link;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.node.TerminationPoint;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * TopologyDataHandler listens to ovsdb topology and propagate significant changes to presto ext topology.
 *
 * @author bartosz.michalik@amartus.com
 */
public class TopologyDataHandler implements DataTreeChangeListener<Topology> {
    private static final Logger LOG = LoggerFactory.getLogger(TopologyDataHandler.class);
    private static final String OVS_ENCAP_TOPOLOGY = "ovs-internal-topo";
    private static final String OVS_NODE = "ovs-node";
    private static final String DELIMETER = ":";
    private static final InstanceIdentifier<Topology> FLOW_TOPO_IID = InstanceIdentifier
            .create(NetworkTopology.class)
            .child(Topology.class, new TopologyKey(new TopologyId(new Uri("flow:1"))));
    private ListenerRegistration<TopologyDataHandler> registration;
    private TopologyTransaction topologyTransaction;
    private DataObjectModificationQualifier dataObjectModificationQualifier;

    private final DataBroker dataBroker;

    public TopologyDataHandler(DataBroker dataBroker) {
        Objects.requireNonNull(dataBroker);
        this.dataBroker = dataBroker;
        topologyTransaction = new TopologyTransaction(dataBroker);
    }

    public void init() {
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();

        NrpDao dao = new NrpDao(tx);
        dao.createSystemNode(OVS_NODE, null);

        Futures.addCallback(tx.submit(), new FutureCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {
                LOG.info("Node {} created", OVS_NODE);
            }

            @Override
            public void onFailure(Throwable t) {
                LOG.error("No node created due to the error", t);
            }
        });

        dataObjectModificationQualifier = new DataObjectModificationQualifier(dataBroker);
        registerFlowTreeListener();
    }

    private void registerFlowTreeListener() {
        registration = dataBroker.registerDataTreeChangeListener(new DataTreeIdentifier<>(LogicalDatastoreType.OPERATIONAL, FLOW_TOPO_IID), this);
    }

    public void close() {
        ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();

        NrpDao dao = new NrpDao(tx);
        dao.removeNode(OVS_NODE, true);

        Futures.addCallback(tx.submit(), new FutureCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {
                LOG.info("Node {} deleted", OVS_NODE);
            }

            @Override
            public void onFailure(Throwable t) {
                LOG.error("No node deleted due to the error", t);
            }
        });

        if (registration != null) {
            LOG.info("closing netconf tree listener");
            registration.close();
        }
    }

    @Override
    public void onDataTreeChanged(@Nonnull Collection<DataTreeModification<Topology>> collection) {
        final List<Node> toAddNodeMap = new ArrayList<>();
        final List<Node> toDeleteNodeMap = new ArrayList<>();
        final List<Node> toUpdateNodeMap = new ArrayList<>();
        final List<Link> toAddLinkMap = new ArrayList<>();
        final List<Link> toDeleteLinkMap = new ArrayList<>();
        final List<Link> toUpdateLinkMap = new ArrayList<>();
        final Map<TerminationPoint,String> toAddTpMap = new HashMap<>();
        final Map<TerminationPoint,String> toDeleteTpMap = new HashMap<>();
        final Map<TerminationPoint,String> toUpdateTpMap = new HashMap<>();

        List<DataObjectModification> topologies = collection.stream()
                .map(DataTreeModification::getRootNode)
                .collect(Collectors.toList());

        dataObjectModificationQualifier.checkTopologies(topologies, toAddNodeMap, toDeleteNodeMap, toUpdateNodeMap,
                toAddLinkMap, toDeleteLinkMap, toUpdateLinkMap,
                toAddTpMap,toUpdateTpMap,toDeleteTpMap);

        executeDbAction(addNodeToOvsInternalTopoAction, toAddNodeMap);
        executeDbAction(updateNodeToOvsInternalTopoAction, toUpdateNodeMap);
        executeDbAction(deleteNodeToOvsInternalTopoAction, toDeleteNodeMap);

        executeDbAction(addLinkToOvsInternalTopoAction, toAddLinkMap);
        executeDbAction(updateLinkToOvsInternalTopoAction, toUpdateLinkMap);
        executeDbAction(deleteLinkToOvsInternalTopoAction, toDeleteLinkMap);

        executeDbAction(addSystemTopoAction,toAddTpMap);
        executeDbAction(updateSystemTopoAction,toUpdateTpMap);
        executeDbAction(deleteSystemTopoAction,toDeleteTpMap);
    }

    BiConsumer<Map<TerminationPoint,String>,NrpDao> updateSystemTopoAction = (map, dao) -> {
        map.entrySet()
                .forEach(entry -> {
//                    if (!isNep(entry.getKey())) {
//                        return;
//                    }
                    String nepId = OVS_NODE + DELIMETER + getFullPortName(entry.getValue(), entry.getKey().getTpId().getValue());
                    OwnedNodeEdgePoint nep;
                    if (dao.hasSip(nepId)) {
                        nep = createNep(nepId);
                        dao.updateNep(OVS_NODE,nep);
                    } else {
                        addEndpoint(dao,nepId);
                    }
                });
    };

    BiConsumer<Map<TerminationPoint,String>,NrpDao> deleteSystemTopoAction = (map, dao) -> {
        map.entrySet()
                .forEach(entry -> {
                    String nepId = OVS_NODE + DELIMETER + getFullPortName(entry.getValue(), entry.getKey().getTpId().getValue());
                    dao.removeNep(OVS_NODE,nepId,true);
                });
    };

    BiConsumer<List<? extends DataObject>,NrpDao> addNodeToOvsInternalTopoAction = (list, dao) -> {
        list.forEach(node -> addNodeToOvsInternalTopo(dao, (Node) node));
    };

    BiConsumer<List<? extends DataObject>,NrpDao> updateNodeToOvsInternalTopoAction = (list, dao) -> {
        list.forEach(node -> updateNodeToOvsInternalTopo(dao, (Node) node));
    };

    BiConsumer<List<? extends DataObject>,NrpDao> deleteNodeToOvsInternalTopoAction = (list, dao) -> {
        list.forEach(node -> deleteNodeToOvsInternalTopo(dao, (Node) node));
    };

    BiConsumer<List<? extends DataObject>,NrpDao> addLinkToOvsInternalTopoAction = (list, dao) -> {
        list.forEach(link -> addLinkToOvsInternalTopo(dao, (Link) link));
    };

    private void addLinkToOvsInternalTopo(NrpDao dao, Link link) {
        LinkBuilder builder = new LinkBuilder();
        List<Uuid> nodeEdgeList = new ArrayList<>();
        nodeEdgeList.add(new Uuid(link.getSource().getSourceTp().getValue()));
        nodeEdgeList.add(new Uuid(link.getDestination().getDestTp().getValue()));
        builder.setUuid(new Uuid(link.getLinkId().getValue())).setNodeEdgePoint(nodeEdgeList);
        dao.updateInternalLink(OVS_ENCAP_TOPOLOGY, builder.build());
    }

    BiConsumer<List<? extends DataObject>,NrpDao> updateLinkToOvsInternalTopoAction = (list, dao) -> {
        list.forEach(link -> updateLinkToOvsInternalTopo(dao, (Link) link));
    };

    private void updateLinkToOvsInternalTopo(NrpDao dao, Link link) {
        addLinkToOvsInternalTopo(dao, link);
    }

    BiConsumer<List<? extends DataObject>,NrpDao> deleteLinkToOvsInternalTopoAction = (list, dao) -> {
        list.forEach(link -> deleteLinkToOvsInternalTopo(dao, (Link) link));
    };

    private void deleteLinkToOvsInternalTopo(NrpDao dao, Link link) {
        LinkBuilder builder = new LinkBuilder();
        List<Uuid> nodeEdgeList = new ArrayList<>();
        nodeEdgeList.add(new Uuid(link.getSource().getSourceTp().getValue()));
        nodeEdgeList.add(new Uuid(link.getDestination().getDestTp().getValue()));
        builder.setUuid(new Uuid(link.getLinkId().getValue())).setNodeEdgePoint(nodeEdgeList);
        dao.deleteInternalLink(OVS_ENCAP_TOPOLOGY, builder.build());
    }

    private void deleteNodeToOvsInternalTopo(NrpDao dao, Node node) {
        dao.deleteInternalNode(OVS_ENCAP_TOPOLOGY, node.getNodeId());
    }

    private void updateNodeToOvsInternalTopo(NrpDao dao, Node node) {
        List<OwnedNodeEdgePoint> edgePoints = new ArrayList<>();
        if (node.getTerminationPoint() != null) {
            edgePoints = node.getTerminationPoint().stream().map(tp -> toOwnedNodeEdgePoint(tp)).collect(Collectors.toList());
        }
        dao.updateInternalNode(OVS_ENCAP_TOPOLOGY, node.getNodeId(), edgePoints);
    }

    private void addNodeToOvsInternalTopo(NrpDao dao, Node node) {
        List<OwnedNodeEdgePoint> edgePoints = new ArrayList<>();
        if (node.getTerminationPoint() != null) {
            edgePoints = node.getTerminationPoint().stream().map(tp -> toOwnedNodeEdgePoint(tp)).collect(Collectors.toList());
        }
        dao.updateInternalNode(OVS_ENCAP_TOPOLOGY, node.getNodeId(), edgePoints);
    }

    private OwnedNodeEdgePoint toOwnedNodeEdgePoint(TerminationPoint tp) {
        Uuid uuid = new Uuid(tp.getTpId().getValue());
        return new OwnedNodeEdgePointBuilder()
                .setUuid(uuid)
                .setKey(new OwnedNodeEdgePointKey(uuid))
//                .setMappedServiceInterfacePoint(Collections.singletonList(sipUuid))
                .build();
    }

    BiConsumer<Map<TerminationPoint,String>,NrpDao> addSystemTopoAction = (map, dao) -> {
        List<OwnedNodeEdgePoint> newNeps = getNewNeps(map);
        newNeps.forEach(nep -> addEndpoint(dao, nep.getKey().getUuid().getValue()));
    };

    private void executeDbAction(BiConsumer<Map<TerminationPoint,String>,NrpDao> action,Map<TerminationPoint,String> map) {
        if (map.isEmpty())
            return ;
        final ReadWriteTransaction topoTx = dataBroker.newReadWriteTransaction();
        NrpDao dao = new NrpDao(topoTx);

        action.accept(map,dao);

        Futures.addCallback(topoTx.submit(), new FutureCallback<Void>() {

            @Override
            public void onSuccess(@Nullable Void result) {
                LOG.debug("Ovs TAPI node action executed successfully");
            }

            @Override
            public void onFailure(Throwable t) {
                LOG.warn("Ovs TAPI node action execution failed due to an error", t);
            }
        });
    }

    private void executeDbAction(BiConsumer<List<? extends DataObject>, NrpDao> action, List<? extends DataObject> list) {
        if (list.isEmpty())
            return ;
        final ReadWriteTransaction topoTx = dataBroker.newReadWriteTransaction();
        NrpDao dao = new NrpDao(topoTx);

        action.accept(list,dao);

        Futures.addCallback(topoTx.submit(), new FutureCallback<Void>() {

            @Override
            public void onSuccess(@Nullable Void result) {
                LOG.debug("Ovs TAPI node action executed successfully");
            }

            @Override
            public void onFailure(Throwable t) {
                LOG.warn("Ovs TAPI node action execution failed due to an error", t);
            }
        });
    }

    private String getFullPortName(String switchName, String portName) {
        return switchName + DELIMETER + portName;
    }

    private void addEndpoint(NrpDao dao, String nepName) {
        ServiceInterfacePoint sip = createSip(nepName);

        dao.addSip(sip);
        dao.updateNep(OVS_NODE, nep(nepName, sip.getUuid()));
    }

    private OwnedNodeEdgePoint nep(String nepName, Uuid sipUuid) {
        Uuid uuid = new Uuid(nepName);
        return new OwnedNodeEdgePointBuilder()
                .setUuid(uuid)
                .setKey(new OwnedNodeEdgePointKey(uuid))
                .setMappedServiceInterfacePoint(Collections.singletonList(sipUuid))
                .build();
    }

    private ServiceInterfacePoint createSip(String nep) {
        Uuid uuid = new Uuid( "sip" + DELIMETER + nep);
        return new ServiceInterfacePointBuilder()
                .setUuid(uuid)
                .setKey(new ServiceInterfacePointKey(uuid))
                .setState(new StateBuilder().setLifecycleState(LifecycleState.Installed).build())
// TODO donaldh .setDirection(TerminationDirection.Bidirectional)
                .build();
    }

    private OwnedNodeEdgePoint createNep(String nepId) {
        OwnedNodeEdgePointBuilder tpBuilder = new OwnedNodeEdgePointBuilder();
        Uuid tpId = new Uuid(OVS_NODE + DELIMETER + nepId);
        return tpBuilder
                .setUuid(tpId)
                .setKey(new OwnedNodeEdgePointKey(tpId))
                .build();
    }

    private List<OwnedNodeEdgePoint> getNewNeps(Map<TerminationPoint,String> toAddMap) {
        return toAddMap.entrySet().stream().filter(entry -> isNep(entry.getKey()))
                .map(entry -> createNep(getFullPortName(entry.getValue(),entry.getKey().getTpId().getValue())) )
                .collect(Collectors.toList());
    }

    //TODO: write better implementation
    private boolean isNep(TerminationPoint terminationPoint) {
//        OvsdbTerminationPointAugmentation ovsdbTerminationPoint = terminationPoint.getAugmentation(OvsdbTerminationPointAugmentation.class);
//        if ( ovsdbTerminationPoint==null || (ovsdbTerminationPoint.getInterfaceType()!=null && ovsdbTerminationPoint.getInterfaceType().equals(InterfaceTypeInternal.class))) {
//            return false;
//        }
//
//        if ( ovsdbTerminationPoint.getOfport() == null )
//            return false;

//        String ofPortNumber = ovsdbTerminationPoint.getOfport().toString();
//        String ofPortNumber = terminationPoint.getTpId().getValue();
        try {
            org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node node = topologyTransaction.readNodeFromTpID(terminationPoint.getTpId().getValue());
            String ofPortName = terminationPoint.getTpId().getValue(); // node.getId().getValue()+":"+ofPortNumber;
            if (ofPortName.contains("LOCAL")) {
                return false;
            }
            List<Link> links = topologyTransaction.readLinks(node);
            return !links.stream()
                    .anyMatch(link -> link.getSource().getSourceTp().getValue().equals(ofPortName));
        } catch (ResourceNotAvailableException e) {
            LOG.warn(e.getMessage());
        }
        return false;
    }

    public static String getOvsNode() {
        return OVS_NODE;
    }
}