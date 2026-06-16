package com.prabu.redistool.commands;

import redis.clients.jedis.Jedis;
import com.prabu.redistool.AnsibleRunner;

import java.util.ArrayList;
import java.util.List;

public class StatusCommand {

    private static final String[][] ALL_NODES = {
            {"127.0.0.1", "6381"},
            {"127.0.0.1", "6382"},
            {"127.0.0.1", "6383"},
            {"127.0.0.1", "6384"},
            {"127.0.0.1", "6385"},
            {"127.0.0.1", "6386"},
            {"127.0.0.1", "6387"},
            {"127.0.0.1", "6388"}
    };

    public static void run(String[] args) throws Exception {
        try {
            printClusterStatus();
        } catch (Exception e) {
            System.err.println("Direct cluster connection failed. Falling back to Ansible status...");
            AnsibleRunner.run("status.yml");
        }
    }

    private static void printClusterStatus() {
        String rawClusterNodes = null;
        String rawClusterInfo = null;

        for (String[] nodeConfig : ALL_NODES) {
            try (Jedis client = new Jedis(nodeConfig[0], Integer.parseInt(nodeConfig[1]))) {
                client.ping(); // Verify connection
                rawClusterNodes = client.clusterNodes();
                rawClusterInfo = client.clusterInfo();
                break;
            } catch (Exception ignored) {
            }
        }

        if (rawClusterNodes == null) {
            System.err.println("ERROR: Cannot connect to any Redis node in the cluster.");
            return;
        }

        String clusterState = "unknown";
        for (String line : rawClusterInfo.split("\n")) {
            if (line.startsWith("cluster_state:")) {
                clusterState = line.split(":")[1].trim();
                break;
            }
        }
        
        System.out.println("Cluster State: " + clusterState + "\n");

        List<NodeInfo> masterNodes = new ArrayList<>();
        List<NodeInfo> replicaNodes = new ArrayList<>();

        for (String line : rawClusterNodes.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            
            NodeInfo node = parseNodeLine(line);
            if (node == null) {
                continue;
            }
            
            if ("master".equals(node.role)) {
                masterNodes.add(node);
            } else {
                replicaNodes.add(node);
            }
        }

        for (NodeInfo node : masterNodes) {
            enrichNodeDetails(node);
        }
        for (NodeInfo node : replicaNodes) {
            enrichNodeDetails(node);
        }

        System.out.println("MASTERS");
        for (NodeInfo master : masterNodes) {
            System.out.printf("  %s:%d [master] v%s slots: %-11s keys: %-4d mem: %s%n",
                    master.ip, master.port, master.version, master.slots, master.keyCount, master.memoryUsage);
        }

        System.out.println("\nREPLICAS");
        for (NodeInfo replica : replicaNodes) {
            String masterAddress = findMasterAddress(masterNodes, replica.masterNodeId);
            System.out.printf("  %s:%d [replica] v%s replicating: %-15s mem: %s%n",
                    replica.ip, replica.port, replica.version, masterAddress, replica.memoryUsage);
        }
    }

    private static NodeInfo parseNodeLine(String line) {
        try {
            String[] parts = line.split(" ");
            
            NodeInfo node = new NodeInfo();
            node.nodeId = parts[0];

            String[] addressParts = parts[1].split("@")[0].split(":");
            node.ip = addressParts[0];
            node.port = Integer.parseInt(addressParts[1]);
            
            String flags = parts[2];
            node.role = flags.contains("master") ? "master" : "replica";
            node.masterNodeId = parts[3];

            if ("master".equals(node.role) && parts.length > 8) {
                StringBuilder slotsBuilder = new StringBuilder();
                for (int i = 8; i < parts.length; i++) {
                    if (i > 8) {
                        slotsBuilder.append(", ");
                    }
                    slotsBuilder.append(parts[i].trim());
                }
                node.slots = slotsBuilder.toString();
            }
            
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private static void enrichNodeDetails(NodeInfo node) {
        int localPort = mapContainerIpToLocalPort(node.ip, node.port);
        
        try (Jedis client = new Jedis("127.0.0.1", localPort)) {
            String serverInfo = client.info("server");
            for (String line : serverInfo.split("\n")) {
                if (line.startsWith("redis_version:")) {
                    node.version = line.split(":")[1].trim();
                }
            }
            
            String memoryInfo = client.info("memory");
            for (String line : memoryInfo.split("\n")) {
                if (line.startsWith("used_memory_human:")) {
                    node.memoryUsage = line.split(":")[1].trim();
                }
            }

            if ("master".equals(node.role)) {
                String keyspaceInfo = client.info("keyspace");
                for (String line : keyspaceInfo.split("\n")) {
                    if (line.startsWith("db")) {
                        for (String metric : line.split(",")) {
                            if (metric.contains("keys=")) {
                                node.keyCount += Integer.parseInt(metric.split("=")[1].trim());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            node.version = "unknown";
            node.memoryUsage = "unknown";
        }
    }

    private static int mapContainerIpToLocalPort(String containerIp, int containerPort) {
        switch (containerIp) {
            case "10.10.0.11": return 6381;
            case "10.10.0.12": return 6382;
            case "10.10.0.13": return 6383;
            case "10.10.0.14": return 6384;
            case "10.10.0.15": return 6385;
            case "10.10.0.16": return 6386;
            case "10.10.0.17": return 6387;
            case "10.10.0.18": return 6388;
            default: return containerPort;
        }
    }

    private static String findMasterAddress(List<NodeInfo> masters, String targetMasterNodeId) {
        for (NodeInfo master : masters) {
            if (master.nodeId.equals(targetMasterNodeId)) {
                return master.ip + ":" + master.port;
            }
        }
        return targetMasterNodeId; // Fallback if not found
    }

    private static class NodeInfo {
        String nodeId;
        String ip;
        int port;
        String role;
        String masterNodeId;
        String slots = "";
        String version = "unknown";
        String memoryUsage = "unknown";
        int keyCount = 0;
    }
}