package com.prabu.redistool.commands;

import redis.clients.jedis.Jedis;
import java.util.*;


public class VerifyCommand {

    private static final int[] LOCAL_PORTS = {6381, 6382, 6383, 6384, 6385, 6386, 6387, 6388};

    public static void run(String[] args) throws Exception {
        System.out.println("=== Full Cluster Verification ===\n");
        
        boolean dataIntegrityOk = checkDataIntegrity();
        boolean versionConsistencyOk = checkVersionConsistency();
        boolean topologyHealthOk = checkTopologyHealth();
        boolean clusterStateOk = checkClusterState();
        boolean replicationLagOk = checkReplicationLag();

        System.out.println("\n=== Verification Summary ===");
        System.out.printf("Data Integrity       : %s%n", (dataIntegrityOk ? "PASS" : "FAIL"));
        System.out.printf("Version Consistency  : %s%n", (versionConsistencyOk ? "PASS" : "FAIL"));
        System.out.printf("Topology Health      : %s%n", (topologyHealthOk ? "PASS" : "FAIL"));
        System.out.printf("Cluster State        : %s%n", (clusterStateOk ? "PASS" : "FAIL"));
        System.out.printf("Replication Lag      : %s%n", (replicationLagOk ? "PASS" : "FAIL"));

        boolean isOverallPass = dataIntegrityOk && versionConsistencyOk && 
                                topologyHealthOk && clusterStateOk && replicationLagOk;
                                
        System.out.println("\nOverall: " + (isOverallPass ? "PASS" : "FAIL"));
        
        if (!isOverallPass) {
            System.exit(1);
        }
    }

    private static boolean checkDataIntegrity() {
        System.out.println("[1/5] Checking data integrity...");
        try {
            DataCommand.verifyData(1000);
            return true;
        } catch (Exception e) {
            System.err.println("  ERROR: Data verification failed - " + e.getMessage());
            return false;
        }
    }

    private static boolean checkVersionConsistency() {
        System.out.println("[2/5] Checking version consistency...");
        Set<String> detectedVersions = new HashSet<>();
        
        for (int port : LOCAL_PORTS) {
            try (Jedis client = new Jedis("127.0.0.1", port)) {
                String serverInfo = client.info("server");
                for (String line : serverInfo.split("\n")) {
                    if (line.startsWith("redis_version:")) {
                        detectedVersions.add(line.split(":")[1].trim());
                    }
                }
            } catch (Exception e) {
                if (port <= 6386) {
                    System.out.println("  WARN: Core node port " + port + " unreachable");
                }
            }
        }
        
        boolean isConsistent = (detectedVersions.size() == 1);
        System.out.println("  Versions found: " + detectedVersions + " — " + (isConsistent ? "PASS" : "FAIL"));
        return isConsistent;
    }

    private static boolean checkTopologyHealth() {
        System.out.println("[3/5] Checking topology health...");

        for (int port : LOCAL_PORTS) {
            try (Jedis client = new Jedis("127.0.0.1", port)) {
                String clusterNodes = client.clusterNodes();
                int totalSlots = 0;
                int masterCount = 0;
                int replicaCount = 0;
                
                for (String line : clusterNodes.split("\n")) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    
                    String[] parts = line.split(" ");
                    String flags = parts[2];
                    
                    if (flags.contains("master")) {
                        masterCount++;
                        for (int i = 8; i < parts.length; i++) {
                            if (parts[i].contains("-")) {
                                String[] range = parts[i].trim().split("-");
                                totalSlots += Integer.parseInt(range[1]) - Integer.parseInt(range[0]) + 1;
                            } else if (!parts[i].startsWith("[")) {
                                totalSlots++;
                            }
                        }
                    } else if (flags.contains("slave") || flags.contains("replica")) {
                        replicaCount++;
                    }
                }
                
                boolean isHealthy = (totalSlots == 16384) && (masterCount >= 3) && (replicaCount >= masterCount);
                System.out.printf("  Slots: %d/16384, Masters: %d, Replicas: %d — %s%n", 
                        totalSlots, masterCount, replicaCount, (isHealthy ? "PASS" : "FAIL"));
                return isHealthy;
                
            } catch (Exception e) {
                // Continue to the next port if this one is unreachable
            }
        }
        
        System.out.println("  ERROR: Could not connect to any node to check topology.");
        return false;
    }

    private static boolean checkClusterState() {
        System.out.println("[4/5] Checking cluster state...");
        
        for (int port : LOCAL_PORTS) {
            try (Jedis client = new Jedis("127.0.0.1", port)) {
                String clusterInfo = client.clusterInfo();
                boolean isStateOk = clusterInfo.contains("cluster_state:ok");
                
                System.out.println("  cluster_state: " + (isStateOk ? "ok — PASS" : "NOT ok — FAIL"));
                return isStateOk;
            } catch (Exception e) {
                // Continue to the next port
            }
        }
        
        System.out.println("  ERROR: Could not connect to any node to check state.");
        return false;
    }

    private static boolean checkReplicationLag() {
        System.out.println("[5/5] Checking replication lag...");
        boolean isAllOk = true;
        
        for (int port : LOCAL_PORTS) {
            try (Jedis client = new Jedis("127.0.0.1", port)) {
                String replicationInfo = client.info("replication");

                if (replicationInfo.contains("role:slave") || replicationInfo.contains("role:replica")) {
                    boolean isLinkUp = replicationInfo.contains("master_link_status:up");
                    System.out.println("  replica at port " + port + " master_link_status: " + (isLinkUp ? "up — ok" : "NOT up — FAIL"));
                    
                    if (!isLinkUp) {
                        isAllOk = false;
                    }
                }
            } catch (Exception e) {
                // Ignore unreachable ports, they might be un-provisioned nodes (e.g. 7 and 8)
            }
        }
        return isAllOk;
    }
}