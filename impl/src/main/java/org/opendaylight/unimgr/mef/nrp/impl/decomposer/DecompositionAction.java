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
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.ReadWriteTransaction;
import org.opendaylight.controller.md.sal.common.api.data.ReadFailedException;
import org.opendaylight.unimgr.mef.nrp.api.EndPoint;
import org.opendaylight.unimgr.mef.nrp.api.Subrequrest;
import org.opendaylight.unimgr.mef.nrp.api.TapiConstants;
import org.opendaylight.unimgr.mef.nrp.common.NrpDao;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapicommon.rev170227.UniversalId;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapitopology.rev170227.topology.Node;
import org.opendaylight.yang.gen.v1.urn.mef.yang.tapitopology.rev170227.topology.context.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author bartosz.michalik@amartus.com
 */
public class DecompositionAction {
    private static final Logger log = LoggerFactory.getLogger(DecompositionAction.class);
    private final List<EndPoint> endpoints;
    private final DataBroker broker;
    private HashMap<UniversalId, Vertex> sipToNep = new HashMap<>();

    public DecompositionAction(List<EndPoint> endpoints, DataBroker broker) {
        Objects.requireNonNull(endpoints);
        Objects.requireNonNull(broker);
        if(endpoints.size() < 2) throw new IllegalArgumentException("there should be at least two endpoints defined");
        this.endpoints = endpoints;
        this.broker = broker;
    }

    List<Subrequrest> decompose() {
        Graph<Vertex, DefaultEdge> graph = prepareData();

        List<Vertex> vertexes = endpoints.stream().map(e -> sipToNep.get(e.getEndpoint().getServiceInterfacePoint())).collect(Collectors.toList());

        assert vertexes.size() > 1;

        if(vertexes.size() > 2) throw new IllegalStateException("currently only point to point is supported");

        GraphPath<Vertex, DefaultEdge> path = DijkstraShortestPath.findPathBetween(graph, vertexes.get(0), vertexes.get(1));

        if(path == null) return null;

        return path.getVertexList().stream().collect(Collectors.groupingBy(v -> v.getNodeUuid()))
                .entrySet().stream().map(e -> {
                    return new Subrequrest(e.getKey(), e.getValue().stream().map(v -> toEndPoint(v)).collect(Collectors.toList()));
        }).collect(Collectors.toList());
    }

    private EndPoint toEndPoint(Vertex v) {
        return endpoints.stream().filter(e -> e.getEndpoint().getServiceInterfacePoint().equals(v.getSip())).findFirst()
                .orElse(new EndPoint(null, null));
    }

    protected Graph<Vertex, DefaultEdge> prepareData() {
        ReadWriteTransaction tx = broker.newReadWriteTransaction();
        try {
            Topology topo = new NrpDao(tx).getTopology(TapiConstants.PRESTO_SYSTEM_TOPO);


            Graph<Vertex, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);
            topo.getNode().stream().map(this::nodeToGraph).forEach(vs -> {
                List<Vertex> vertexes = vs.collect(Collectors.toList());
                for(int i = 0; i < vertexes.size(); ++i) {
                    Vertex f = vertexes.get(i);
                    sipToNep.put(f.getSip(), f);
                    //its OK if the vertex is added in internal loop nothing will happen
                    graph.addVertex(f);
                    for(int j = i + 1; j < vertexes.size(); ++j) {
                        Vertex t = vertexes.get(j);
                        graph.addVertex(t);
                        graph.addEdge(f,t);
                    }
                }
            });


            return graph;
        } catch (ReadFailedException e) {
            log.warn("Cannot read {} topology", TapiConstants.PRESTO_SYSTEM_TOPO);
            return null;
        }
    }

    protected Stream<Vertex> nodeToGraph(Node n) {
        UniversalId nodeUuid = n.getUuid();
        return n.getOwnedNodeEdgePoint().stream().map(nep -> {
            List<UniversalId> sips = nep.getMappedServiceInterfacePoint();
            if(sips == null || sips.isEmpty()) {
                return  new Vertex(nodeUuid, nep.getUuid(), null);
            }
            if(sips.size() > 1) log.warn("NodeEdgePoint {} have multiple ServiceInterfacePoint mapped, selecting first one", nep.getUuid());
            return new Vertex(nodeUuid, nep.getUuid(), sips.get(0));

        });
    }

    public class Vertex implements Comparable<Vertex> {

        private final UniversalId nodeUuid;
        private final UniversalId uuid;
        private final UniversalId sip;

        public Vertex(UniversalId nodeUuid, UniversalId uuid, UniversalId sip) {
            this.sip = sip;
            Objects.requireNonNull(nodeUuid);
            Objects.requireNonNull(uuid);
            this.nodeUuid = nodeUuid;
            this.uuid = uuid;
        }

        public UniversalId getNodeUuid() {
            return nodeUuid;
        }

        public UniversalId getUuid() {
            return uuid;
        }

        public UniversalId getSip() {
            return sip;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Vertex vertex = (Vertex) o;
            return Objects.equals(uuid, vertex.uuid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uuid);
        }

        @Override
        public int compareTo(Vertex o) {
            if(o == null) return -1;
            return uuid.getValue().compareTo(o.uuid.getValue());
        }
    }
}
