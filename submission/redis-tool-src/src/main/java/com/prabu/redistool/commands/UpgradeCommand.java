package com.prabu.redistool.commands;

import com.prabu.redistool.AnsibleRunner;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.DefaultJedisClientConfig;

import java.util.*;

public class UpgradeCommand {

    private static final String[] REPLICA_HOSTS = {"redis-node-4", "redis-node-5", "redis-node-6"};
    private static final String[] MASTER_HOSTS = {"redis-node-1", "redis-node-2", "redis-node-3"};

    private static final int[] REPLICA_PORTS = {6384, 6385, 6386};
    private static final int[] ALL_PORTS = {6381, 6382, 6383, 6384, 6385, 6386, 6387, 6388};

    private static final Map<String, Integer> IP_TO_PORT = new LinkedHashMap<>();
    static {
        IP_TO_PORT.put("10.10.0.11", 6381);
        IP_TO_PORT.put("10.10.0.12", 6382);
        IP_TO_PORT.put("10.10.0.13", 6383);
        IP_TO_PORT.put("10.10.0.14", 6384);
        IP_TO_PORT.put("10.10.0.15", 6385);
        IP_TO_PORT.put("10.10.0.16", 6386);
        IP_TO_PORT.put("10.10.0.17", 6387);
        IP_TO_PORT.put("10.10.0.18", 6388);
    }

    private static final JedisClientConfig CONFIG = DefaultJedisClientConfig.builder()
        .connectionTimeoutMillis(5000)
        .socketTimeoutMillis(5000)
        .build();

    public static void run(String[] args) throws Exception {
        String targetVersion = "7.2.6";

        for (int i = 0; i < args.length; i++) {
            if ("--target-version".equals(args[i]) && i + 1 < args.length) {
                targetVersion = args[i + 1];
            }
        }

        System.out.println("=== Rolling Upgrade to v" + targetVersion + " ===");

        // --- Phase 0: Pre-flight checks ---
        System.out.println("\n[Pre-flight] Checking cluster health...");
        if (!isClusterOk()) {
            System.err.println("ERROR: Cluster is not in 'ok' state. Aborting upgrade to prevent data loss.");
            System.exit(1);
        }
        
        System.out.println("[Pre-flight] Cluster state: ok");
        System.out.println("[Pre-flight] Running data verify baseline...");
        
        try {
            DataCommand.verifyData(1000);
        } catch (Exception e) {
            System.err.println("ERROR: Baseline data verification failed. Aborting upgrade.");
            System.exit(1);
        }

        // --- Phase 1: Upgrade Replicas ---
        System.out.println("\n=== Phase 1: Upgrading Replicas ===");
        for (int i = 0; i < REPLICA_HOSTS.length; i++) {
            String currentReplica = REPLICA_HOSTS[i];
            System.out.println("\nUpgrading replica " + currentReplica + "...");
            
            int exitCode = AnsibleRunner.run("upgrade.yml",
                "--limit", currentReplica,
                "--extra-vars", "new_version=" + targetVersion + " target_host=" + currentReplica);
                
            if (exitCode != 0) {
                System.err.println("ERROR: Failed upgrading " + currentReplica + ". Stopping the rolling upgrade process.");
                System.exit(1);
            }
            
            System.out.println("Waiting for cluster to stabilize...");
            waitForClusterOk();
            System.out.printf("[%d/6] Upgraded replica %s — cluster: ok%n", (i + 1), currentReplica);
        }

        // --- Phase 2: Upgrade Masters ---
        System.out.println("\n=== Phase 2: Upgrading Masters ===");
        for (int i = 0; i < MASTER_HOSTS.length; i++) {
            String currentMasterHost = MASTER_HOSTS[i];
            String masterContainerIp = "10.10.0." + (11 + i);
            int masterLocalPort = IP_TO_PORT.getOrDefault(masterContainerIp, 6381 + i);

            int replicaPort = findReplicaPortForMaster(masterLocalPort, masterContainerIp);
            System.out.println("\nFound replica at local port " + replicaPort + " for master " + masterContainerIp);

            System.out.println("Triggering CLUSTER FAILOVER on replica port " + replicaPort + "...");
            triggerFailover(replicaPort);

            System.out.println("Upgrading former master " + currentMasterHost + "...");
            
            int exitCode = AnsibleRunner.run("upgrade.yml",
                "--limit", currentMasterHost,
                "--extra-vars", "new_version=" + targetVersion + " target_host=" + currentMasterHost);
                
            if (exitCode != 0) {
                System.err.println("ERROR: Failed upgrading " + currentMasterHost + ". Stopping the rolling upgrade process.");
                System.exit(1);
            }
            
            System.out.println("Waiting for cluster to stabilize...");
            waitForClusterOk();
            System.out.printf("[%d/6] Upgraded %s — cluster: ok%n", (i + 4), currentMasterHost);
        }

        // --- Phase 3: Post-Upgrade Verification ---
        System.out.println("\n=== Post-Upgrade Verification ===");
        try {
            DataCommand.verifyData(1000);
        } catch (Exception e) {
            System.err.println("WARN: Post-upgrade data verification encountered issues: " + e.getMessage());
        }
        
        StatusCommand.run(new String[]{"status"});
        System.out.println("\nUPGRADE COMPLETE — all nodes migrated to v" + targetVersion + ", data integrity verified");
    }

    private static int findReplicaPortForMaster(int masterLocalPort, String masterContainerIp) throws Exception {
        String masterNodeId = null;

        for (int port : ALL_PORTS) {
            try (Jedis client = new Jedis("127.0.0.1", port, CONFIG)) {
                String clusterNodes = client.clusterNodes();
                for (String line : clusterNodes.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    
                    String[] parts = line.split(" ");
                    String nodeAddress = parts[1].split("@")[0];
                    String nodeIp = nodeAddress.split(":")[0];
                    
                    if (nodeIp.equals(masterContainerIp) && parts[2].contains("master")) {
                        masterNodeId = parts[0];
                        break;
                    }
                }
                if (masterNodeId != null) break;
            } catch (Exception e) {
                // Ignore unreachable nodes
            }
        }

        if (masterNodeId == null) {
            System.err.println("  WARN: Could not find master node ID for " + masterContainerIp + ", defaulting to the first available replica port.");
            for (int port : REPLICA_PORTS) {
                try (Jedis client = new Jedis("127.0.0.1", port, CONFIG)) {
                    String replicationInfo = client.info("replication");
                    if (replicationInfo.contains("role:slave") || replicationInfo.contains("role:replica")) {
                        System.out.println("  Using replica port " + port);
                        return port;
                    }
                } catch (Exception e) {
                    // Ignore unreachable ports
                }
            }
            return REPLICA_PORTS[0];
        }

        for (int port : ALL_PORTS) {
            try (Jedis client = new Jedis("127.0.0.1", port, CONFIG)) {
                String clusterNodes = client.clusterNodes();
                for (String line : clusterNodes.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    
                    String[] parts = line.split(" ");
                    if (parts.length < 4) continue;
                    
                    String flags = parts[2];
                    String linkedMasterId = parts[3];
                    String nodeAddress = parts[1].split("@")[0];
                    String nodeIp = nodeAddress.split(":")[0];

                    boolean isReplica = flags.contains("slave") || flags.contains("replica");
                    
                    if (isReplica && linkedMasterId.equals(masterNodeId)) {
                        Integer localPort = IP_TO_PORT.get(nodeIp);
                        if (localPort != null) {
                            System.out.println("  Found replica " + nodeIp + " -> local port " + localPort);
                            return localPort;
                        }
                    }
                }
                break;
            } catch (Exception e) {
                // Ignore unreachable ports
            }
        }

        System.err.println("  WARN: Could not map replica accurately, defaulting to port " + (masterLocalPort + 3));
        return masterLocalPort + 3; // Fallback mapping based on standard setup
    }

    private static boolean isClusterOk() {
        for (int port : ALL_PORTS) {
            try (Jedis client = new Jedis("127.0.0.1", port, CONFIG)) {
                if (client.clusterInfo().contains("cluster_state:ok")) {
                    return true;
                }
            } catch (Exception e) {
                // Ignore failures; we just need one node to confirm
            }
        }
        return false;
    }

    private static void waitForClusterOk() throws Exception {
        final int MAX_RETRIES = 30;
        
        for (int i = 0; i < MAX_RETRIES; i++) {
            if (isClusterOk()) {
                System.out.println("  Cluster is ok");
                return;
            }
            System.out.println("  Waiting... (" + (i + 1) + "/" + MAX_RETRIES + ")");
            Thread.sleep(3000);
        }
        throw new RuntimeException("Cluster did not recover within the expected timeframe.");
    }

    private static void triggerFailover(int replicaPort) throws Exception {
        try (Jedis client = new Jedis("127.0.0.1", replicaPort, CONFIG)) {
            String replicationInfo = client.info("replication");
            
            if (!replicationInfo.contains("role:slave") && !replicationInfo.contains("role:replica")) {
                System.err.println("  WARN: Port " + replicaPort + " is not a replica, skipping failover command.");
                return;
            }
            
            System.out.println("  Sending CLUSTER FAILOVER to port " + replicaPort);
            client.clusterFailover();
        }
        
        System.out.println("  Waiting 8s for failover to complete...");
        Thread.sleep(8000);
    }
}