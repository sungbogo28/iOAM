/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.sfc.pot.netconf.renderer.provider.api;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.MountPoint;
import org.opendaylight.controller.md.sal.binding.api.MountPointService;
import org.opendaylight.controller.sal.binding.api.BindingAwareBroker;
import org.opendaylight.controller.sal.binding.api.BindingAwareBroker.ProviderContext;
import org.opendaylight.controller.sal.binding.api.BindingAwareProvider;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.IpAddress;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.NetconfNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.NetconfNodeConnectionStatus.ConnectionStatus;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyKey;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeKey;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;

public class SfcPotNetconfNodeManager implements BindingAwareProvider {

    private static final Logger LOG = LoggerFactory.getLogger(SfcPotNetconfNodeManager.class);

    private MountPointService mountService;
    private final TopologyId topologyId = new TopologyId("topology-netconf");
    private final String potStr;

    // Data
    private final Map<NodeId, Node> connectedNodes = new HashMap<>();
    private final Map<NodeId, DataBroker> activeMountPoints = new HashMap<>();

    public SfcPotNetconfNodeManager(DataBroker dataBroker, BindingAwareBroker bindingAwareBroker, String potStr) {
        // Register provider
        ProviderContext providerContext = bindingAwareBroker.registerProvider(this);
        onSessionInitiated(providerContext);
        this.potStr = potStr;
    }

    public void updateNode(Node node, boolean ioamPotCapable) {
        NetconfNode netconfNode = node.getAugmentation(NetconfNode.class);
        Preconditions.checkNotNull(netconfNode);
        // Check connection status
        ConnectionStatus connectionStatus = netconfNode.getConnectionStatus();
        NodeId netconfNodeId = node.getNodeId();
        if (connectionStatus.equals(ConnectionStatus.Connected)) {
            connectedNodes.put(netconfNodeId, node);
            // Get mountpoint
            InstanceIdentifier mountPointIid = getMountPointIid(netconfNodeId);

            //If ioam capable, register nodes.
            if (ioamPotCapable) {
                DataBroker dataBroker = getNetconfNodeDataBroker(mountPointIid);
                if (dataBroker != null) {
                    activeMountPoints.put(netconfNodeId, dataBroker);
                } else {
                    LOG.debug("Cannot obtain data broker for netconf node {}", netconfNodeId.getValue());
                    connectedNodes.remove(netconfNodeId);
                }
            } else {
                LOG.debug("Netconf node {} not iOAM capable...", netconfNodeId.getValue());
            }
        }
    }

    public void removeNode(Node node) {
        NetconfNode netconfNode = node.getAugmentation(NetconfNode.class);
        Preconditions.checkNotNull(netconfNode);
        ConnectionStatus connectionStatus = netconfNode.getConnectionStatus();
        NodeId netconfNodeId = node.getNodeId();
        switch (connectionStatus) {
            case Connected:
                connectedNodes.remove(netconfNodeId);
                activeMountPoints.remove(netconfNodeId);
                LOG.info("Netconf node {} removed", netconfNodeId.getValue());
                break;
            default:
                break;
        }
    }

    private DataBroker getNetconfNodeDataBroker(InstanceIdentifier mountPointIid) {
        Optional<MountPoint> optionalObject = mountService.getMountPoint(mountPointIid);
        MountPoint mountPoint;
        if (optionalObject.isPresent()) {
            mountPoint = optionalObject.get();
            if (mountPoint != null) {
                Optional<DataBroker> optionalDataBroker = mountPoint.getService(DataBroker.class);
                if (optionalDataBroker.isPresent()) {
                    return optionalDataBroker.get();
                } else {
                    LOG.debug("Cannot obtain data broker from mountpoint {}", mountPoint);
                }
            } else {
                LOG.debug("Cannot obtain mountpoint with IID {}", mountPointIid);
            }
        }
        return null;
    }

    private InstanceIdentifier getMountPointIid(NodeId nodeId) {
        return InstanceIdentifier.builder(NetworkTopology.class)
                .child(Topology.class, new TopologyKey(topologyId))
                .child(Node.class, new NodeKey(nodeId)).build();
    }

    DataBroker getMountpointFromIpAddress(IpAddress ipAddress) {
        for (Node node : connectedNodes.values()) {
            if (ipAddress.equals(getNetconfNodeIp(node))) {
                return activeMountPoints.get(node.getNodeId());
            }
        }
        return null;
    }

    public NodeId getNodeIdFromIpAddress(IpAddress ipAddress) {
        for (Node node : connectedNodes.values()) {
            if (ipAddress.equals(getNetconfNodeIp(node))) {
                return node.getNodeId();
            }
        }
        return null;
    }

    IpAddress getNetconfNodeIp(Node node) {
        NetconfNode netconfNode = node.getAugmentation(NetconfNode.class);
        Preconditions.checkNotNull(netconfNode);
        return netconfNode.getHost().getIpAddress();
    }

    Map<NodeId, Node> getConnectedNodes() {
        return connectedNodes;
    }

    Map<NodeId, DataBroker> getActiveMountPoints() {
        return activeMountPoints;
    }

    @Override
    public void onSessionInitiated(ProviderContext session) {
        mountService = session.getSALService(MountPointService.class);
        Preconditions.checkNotNull(mountService);
    }
}