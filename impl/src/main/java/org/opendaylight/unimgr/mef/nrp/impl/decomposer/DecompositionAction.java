/*
 * Copyright (c) 2017 Cisco Systems Inc and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.unimgr.mef.nrp.impl.decomposer;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.ReadWriteTransaction;
import org.opendaylight.controller.md.sal.common.api.data.ReadFailedException;
import org.opendaylight.unimgr.mef.nrp.api.EndPoint;
import org.opendaylight.unimgr.mef.nrp.api.FailureResult;
import org.opendaylight.unimgr.mef.nrp.api.Subrequrest;
import org.opendaylight.unimgr.mef.nrp.api.TapiConstants;
import org.opendaylight.unimgr.mef.nrp.common.NrpDao;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.OperationalState;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.PortDirection;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.common.rev170712.Uuid;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.topology.Node;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapi.topology.rev170712.topology.context.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author bartosz.michalik@amartus.com
 */
public class DecompositionAction {
    private static final Logger LOG = LoggerFactory.getLogger(DecompositionAction.class);
    private final List<EndPoint> endpoints;
    private final DataBroker broker;
    private HashMap<Uuid, Vertex> sipToNep = new HashMap<>();

    public DecompositionAction(List<EndPoint> endpoints, DataBroker broker) {
        Objects.requireNonNull(endpoints);
        Objects.requireNonNull(broker);
        if (endpoints.size() < 2) {
            throw new IllegalArgumentException("there should be at least two endpoints defined");
        }
        this.endpoints = endpoints;
        this.broker = broker;
    }

    List<Subrequrest> decompose() throws FailureResult {
        Graph<Vertex, DefaultEdge> graph = prepareData();

        List<Vertex> vertices = endpoints.stream().map(e -> sipToNep.get(e.getEndpoint().getServiceInterfacePoint())).collect(Collectors.toList());

        assert vertices.size() > 1;

        Set<GraphPath<Vertex, DefaultEdge>> paths = new HashSet<>();

        Set<Vertex> inV = vertices.stream().filter(isInput).collect(Collectors.toSet());
        Set<Vertex> outV = vertices.stream().filter(isOuput).collect(Collectors.toSet());

        //do the verification whether it is possible to connect two nodes.
        inV.forEach(i -> {
            outV.stream().filter(o -> i != o).forEach(o -> {
                GraphPath<Vertex, DefaultEdge> path = DijkstraShortestPath.findPathBetween(graph, i, o);
                if(path != null) {
                    paths.add(path);
                }
            });
        });

        List<Subrequrest> result = paths.stream()
                .flatMap(gp -> gp.getVertexList().stream()).collect(Collectors.groupingBy(Vertex::getNodeUuid))
                .entrySet().stream()
                .map(e -> {
                    Set<EndPoint> endpoints = e.getValue().stream().map(this::toEndPoint).collect(Collectors.toSet());
                    return new Subrequrest(e.getKey(), new ArrayList<>(endpoints));
                }).collect(Collectors.toList());
        return result.isEmpty() ? null : result;
    }

    private EndPoint toEndPoint(Vertex v) {
        EndPoint ep = endpoints.stream().filter(e -> e.getEndpoint().getServiceInterfacePoint().equals(v.getSip())).findFirst()
                .orElse(new EndPoint(null, null));
        ep.setSystemNepUuid(v.getUuid());
        return ep;
    }

    private Predicate<Vertex> isInput = v -> v.getDir() == PortDirection.Bidirectional || v.getDir() == PortDirection.Input;
    private Predicate<Vertex> isOuput = v -> v.getDir() == PortDirection.Bidirectional || v.getDir() == PortDirection.Output;

    private void interconnectNode(Graph graph, List<Vertex> vertices) {
        vertices.forEach(v -> graph.addVertex(v));
        Set<Vertex> inV = vertices.stream().filter(isInput).collect(Collectors.toSet());
        Set<Vertex> outV = vertices.stream().filter(isOuput).collect(Collectors.toSet());
        interconnect(graph, inV, outV);
    }

    private void interconnectLink(Graph graph, List<Vertex> vertices) {
        vertices.forEach(v -> graph.addVertex(v));
        Set<Vertex> inV = vertices.stream().filter(isInput).collect(Collectors.toSet());
        Set<Vertex> outV = vertices.stream().filter(isOuput).collect(Collectors.toSet());
        interconnect(graph, outV, inV);


    }

    private void interconnect(Graph<Vertex, DefaultEdge> graph, Collection<Vertex> from, Collection<Vertex> to) {
        from.forEach(iV -> {
            to.stream().filter(oV -> iV != oV).forEach(oV -> {
                graph.addEdge(iV,oV);
            });
        });
    }


    protected Graph<Vertex, DefaultEdge> prepareData() throws FailureResult {
        ReadWriteTransaction tx = broker.newReadWriteTransaction();
        try {
            Topology topo = new NrpDao(tx).getTopology(TapiConstants.PRESTO_SYSTEM_TOPO);
            if (topo.getNode() == null) {
                throw new FailureResult("There are no nodes in {0} topology", TapiConstants.PRESTO_SYSTEM_TOPO);
            }

            Graph<Vertex, DefaultEdge> graph = new DefaultDirectedGraph<Vertex, DefaultEdge>(DefaultEdge.class);

            topo.getNode().stream().map(this::nodeToGraph).forEach(vs -> {
                List<Vertex> vertices = vs.collect(Collectors.toList());
                vertices.forEach(v -> sipToNep.put(v.getSip(), v));
                interconnectNode(graph, vertices);
            });

            if (topo.getLink() != null) {
                topo.getLink().stream()
                        .filter(l -> l.getState() != null && OperationalState.Enabled == l.getState().getOperationalState())
                        .forEach(l -> {
                            //we probably need to take link bidir/unidir into consideration as well
                    List<Vertex> vertices = l.getNodeEdgePoint().stream()
                            .map(nep -> graph.vertexSet().stream().filter(v -> v.getUuid().equals(nep)).findFirst())
                            .filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList());
                    interconnectLink(graph, vertices);
                });
            }

            return graph;
        } catch (ReadFailedException e) {
            throw new FailureResult("Cannot read {0} topology", TapiConstants.PRESTO_SYSTEM_TOPO);
        }
    }

    protected Stream<Vertex> nodeToGraph(Node n) {
        Uuid nodeUuid = n.getUuid();


        return n.getOwnedNodeEdgePoint().stream()
                .filter(ep -> ep.getLinkPortDirection() != null && ep.getLinkPortDirection() != PortDirection.UnidentifiedOrUnknown)
                .map(nep -> {
            List<Uuid> sips = nep.getMappedServiceInterfacePoint();
            if (sips == null || sips.isEmpty()) {
                return  new Vertex(nodeUuid, nep.getUuid(), null, nep.getLinkPortDirection());
            }
            if (sips.size() > 1) {
                LOG.warn("NodeEdgePoint {} have multiple ServiceInterfacePoint mapped, selecting first one", nep.getUuid());
            }
            return new Vertex(nodeUuid, nep.getUuid(), sips.get(0), nep.getLinkPortDirection());

        });
    }

    public class Vertex implements Comparable<Vertex> {

        private final Uuid nodeUuid;
        private final Uuid uuid;
        private final Uuid sip;
        private final PortDirection dir;

        public Vertex(Uuid nodeUuid, Uuid uuid, Uuid sip, PortDirection dir) {
            this.sip = sip;
            this.dir = dir;
            Objects.requireNonNull(nodeUuid);
            Objects.requireNonNull(uuid);
            this.nodeUuid = nodeUuid;
            this.uuid = uuid;
        }

        public Uuid getNodeUuid() {
            return nodeUuid;
        }

        public Uuid getUuid() {
            return uuid;
        }

        public Uuid getSip() {
            return sip;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Vertex vertex = (Vertex) o;
            return Objects.equals(uuid, vertex.uuid);
        }

        public PortDirection getDir() {
            return dir;
        }

        @Override
        public int hashCode() {
            return Objects.hash(uuid);
        }

        @Override
        public int compareTo(Vertex o) {
            if (o == null) {
                return -1;
            }
            return uuid.getValue().compareTo(o.uuid.getValue());
        }

        @Override
        public String toString() {
            return "V{" + uuid.getValue() + '}';
        }
    }
}
