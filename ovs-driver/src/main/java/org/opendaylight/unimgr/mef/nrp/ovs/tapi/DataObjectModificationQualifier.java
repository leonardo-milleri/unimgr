package org.opendaylight.unimgr.mef.nrp.ovs.tapi;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.DataObjectModification;
import org.opendaylight.unimgr.utils.CapabilitiesService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.rev150105.OvsdbBridgeAugmentation;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Link;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.node.TerminationPoint;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.opendaylight.unimgr.utils.CapabilitiesService.Capability.Mode.AND;
import static org.opendaylight.unimgr.utils.CapabilitiesService.NodeContext.NodeCapability.OVSDB;

/**
 * Class created to classify object according to its modification types.
 *
 * @author marek.ryznar@amartus.com
 */
public class DataObjectModificationQualifier {
    private static final Logger LOG = LoggerFactory.getLogger(DataObjectModificationQualifier.class);
    private CapabilitiesService capabilitiesService;

    public DataObjectModificationQualifier(DataBroker dataBroker) {
        capabilitiesService = new CapabilitiesService(dataBroker);
    }

    private Function<Node, Boolean> isOvs = node -> capabilitiesService.node(node).isSupporting(AND, OVSDB);

    protected void checkTopologies(List<DataObjectModification> topologies, List<Node> toAddNodeList, List<Node> toDeleteNodeList, List<Node> toUpdateNodeList,
                                   List<Link> toAddLinkList, List<Link> toDeleteLinkList, List<Link> toUpdateLinkList,
                                   Map<TerminationPoint, String> toAddMap, Map<TerminationPoint, String> toUpdateMap, Map<TerminationPoint, String> toDeleteMap) {
        Topology t;
        for (DataObjectModification topology : topologies) {
            switch (topology.getModificationType()) {
                //new flow:1 topology
                case WRITE: {
                    t = (Topology) topology.getDataAfter();
                    if (t.getNode() != null) {
                        t.getNode().stream().forEach(node -> {
                            toAddNodeList.add(node);
                            String bridgeName = node.getNodeId().getValue();
                            if (node.getTerminationPoint() != null) {
                                node.getTerminationPoint().forEach(tp -> toAddMap.put(tp, bridgeName));
                            }
                        });
                    }
                    if (t.getLink() != null) {
                        t.getLink().stream().filter(link -> link.getSource() != null && link.getDestination() != null).forEach(link -> toAddLinkList.add(link));
                    }
                }
                break;
                case SUBTREE_MODIFIED: {
                    checkNodes(topology, toAddNodeList, toUpdateNodeList, toDeleteNodeList);
                    checkLinks(topology, toAddLinkList, toUpdateLinkList, toDeleteLinkList);
                    checkTerminationPoints(topology, toAddMap, toUpdateMap, toDeleteMap);
                }
                break;
                //whole ovs-node eg. s1 deleted
                case DELETE: {
                    t = (Topology) topology.getDataBefore();

                    if (t.getNode() != null) {
                        t.getNode().stream().forEach(node -> {
                            toDeleteNodeList.add(node);
                            String bridgeName = node.getNodeId().getValue();
                            if (node.getTerminationPoint() != null) {
                                node.getTerminationPoint().forEach(tp -> toDeleteMap.put(tp, bridgeName));
                            }
                        });
                    }
                    if (t.getLink() != null) {
                        t.getLink().stream().filter(link -> link.getSource() != null && link.getDestination() != null).forEach(link -> toDeleteLinkList.add(link));
                    }
                }
                break;
                default: {
                    LOG.debug("Not supported modification type: {}", topology.getModificationType());
                }
                break;
            }
        }
    }

    private void checkNodes(DataObjectModification topology, List<Node> toAdd, List<Node> toUpdate, List<Node> toDelete) {
        Topology t = (Topology) topology.getDataAfter();
        Collection<DataObjectModification<? extends DataObject>> modifiedChildren = topology.getModifiedChildren();

        Node node;
        for (DataObjectModification n : modifiedChildren) {
            if (!n.getDataType().equals(Node.class))
                continue;
            switch (n.getModificationType()) {
                case WRITE: {
                    node = (Node) n.getDataAfter();
                    toAdd.add(node);
                }
                break;
                case SUBTREE_MODIFIED: {
                    node = (Node) n.getDataAfter();
                    if (!n.getDataBefore().equals(n.getDataAfter()))
                        toUpdate.add(node);
                }
                break;
                case DELETE: {
                    node = (Node) n.getDataBefore();
                    toDelete.add(node);
                }
                break;
                default: {
                    LOG.debug("Not supported modification type: SUBTREE_MODIFIED.{}", n.getModificationType());
                }
                break;
            }
        }
    }

    private void checkLinks(DataObjectModification topology, List<Link> toAdd, List<Link> toUpdate, List<Link> toDelete) {
        Topology t = (Topology) topology.getDataAfter();
        Collection<DataObjectModification<? extends DataObject>> modifiedChildren = topology.getModifiedChildren();

        Link link;
        for (DataObjectModification n : modifiedChildren) {
            if (!n.getDataType().equals(Link.class))
                continue;
            switch (n.getModificationType()) {
                case WRITE: {
                    link = (Link) n.getDataAfter();
                    toAdd.add(link);
                }
                break;
                case SUBTREE_MODIFIED: {
                    link = (Link) n.getDataAfter();
                    if (!n.getDataBefore().equals(n.getDataAfter()))
                        toUpdate.add(link);
                }
                break;
                case DELETE: {
                    link = (Link) n.getDataBefore();
                    toDelete.add(link);
                }
                break;
                default: {
                    LOG.debug("Not supported modification type: SUBTREE_MODIFIED.{}", n.getModificationType());
                }
                break;
            }
        }
    }

    private void checkTerminationPoints(DataObjectModification topology, Map<TerminationPoint, String> toAddMap, Map<TerminationPoint, String> toUpdateMap, Map<TerminationPoint, String> toDeleteMap) {
        Topology t = (Topology) topology.getDataAfter();

        Collection<DataObjectModification<? extends DataObject>> children = topology.getModifiedChildren();
        children.stream().filter(child -> child.getDataType().equals(Node.class)).forEach(nodeChanged -> {
            if (nodeChanged.getModificationType() != DataObjectModification.ModificationType.DELETE) {
                String bridgeName = ((Node) nodeChanged.getDataAfter()).getNodeId().getValue();
                nodeChanged.getModifiedChildren().stream().filter(child -> child.getDataType().equals(TerminationPoint.class)).forEach(tp -> {
                    TerminationPoint terminationPoint;
                    switch (tp.getModificationType()) {
                        //new port added eg. s1-eth7
                        case WRITE: {
                            terminationPoint = (TerminationPoint) tp.getDataAfter();
                            toAddMap.put(terminationPoint, bridgeName);
                        }
                        break;
                        case SUBTREE_MODIFIED: {
                            terminationPoint = (TerminationPoint) tp.getDataAfter();
                            if (!tp.getDataBefore().equals(tp.getDataAfter()))
                                toUpdateMap.put(terminationPoint, bridgeName);
                        }
                        break;
                        case DELETE: {
                            terminationPoint = (TerminationPoint) tp.getDataBefore();
                            toDeleteMap.put(terminationPoint, bridgeName);
                        }
                        break;
                        default: {
                            LOG.debug("Not supported modification type: SUBTREE_MODIFIED.{}", tp.getModificationType());
                        }
                        break;
                    }
                });
            }
        });
    }
}
