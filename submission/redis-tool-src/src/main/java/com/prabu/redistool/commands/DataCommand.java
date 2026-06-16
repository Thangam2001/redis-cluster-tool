package com.prabu.redistool.commands;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.exceptions.JedisRedirectionException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class DataCommand {

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

    private static final int[] ALL_PORTS = {6381, 6382, 6383, 6384, 6385, 6386, 6387, 6388};

    private static final JedisClientConfig CONFIG = DefaultJedisClientConfig.builder()
        .connectionTimeoutMillis(3000)
        .socketTimeoutMillis(3000)
        .build();

    public static void run(String[] args) throws Exception {
        if (args.length < 2) {
            printUsageAndExit();
        }

        String subCommand = args[1];
        int numKeys = 1000;

        for (int i = 2; i < args.length; i++) {
            if ("--keys".equals(args[i]) && i + 1 < args.length) {
                try {
                    numKeys = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    System.err.println("ERROR: --keys must be a valid integer.");
                    System.exit(1);
                }
            }
        }

        switch (subCommand) {
            case "seed":
                seedData(numKeys);
                break;
            case "verify":
                verifyData(numKeys);
                break;
            default:
                System.err.println("ERROR: Unknown subcommand '" + subCommand + "'");
                printUsageAndExit();
        }
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: redis-tool data <seed|verify> [--keys N]");
        System.exit(1);
    }

    public static String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        
        for (byte b : hashBytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    private static Jedis connect(int port) {
        return new Jedis("127.0.0.1", port, CONFIG);
    }

    private static void setKey(String key, String value) throws Exception {
        for (int port : ALL_PORTS) {
            try (Jedis client = connect(port)) {
                try {
                    client.set(key, value);
                    return; // Success
                } catch (JedisRedirectionException redirectEx) {
                    // Cluster tells us the key belongs to a different node
                    String targetHost = redirectEx.getTargetNode().getHost();
                    Integer targetLocalPort = IP_TO_PORT.get(targetHost);
                    int finalPort = (targetLocalPort != null) ? targetLocalPort : redirectEx.getTargetNode().getPort();
                    
                    try (Jedis redirectClient = connect(finalPort)) {
                        redirectClient.set(key, value);
                        return; // Success after redirect
                    }
                }
            } catch (Exception e) {
            }
        }
        throw new Exception("Could not set key: " + key + ". All nodes unreachable.");
    }

    private static String getKey(String key) throws Exception {
        for (int port : ALL_PORTS) {
            try (Jedis client = connect(port)) {
                try {
                    return client.get(key);
                } catch (JedisRedirectionException redirectEx) {
                    String targetHost = redirectEx.getTargetNode().getHost();
                    Integer targetLocalPort = IP_TO_PORT.get(targetHost);
                    int finalPort = (targetLocalPort != null) ? targetLocalPort : redirectEx.getTargetNode().getPort();
                    
                    try (Jedis redirectClient = connect(finalPort)) {
                        return redirectClient.get(key);
                    }
                }
            } catch (Exception e) {
            }
        }
        return null;
    }

    private static void seedData(int numberOfKeys) throws Exception {
        int insertedCount = 0;
        int failureCount = 0;

        System.out.println("Seeding " + numberOfKeys + " keys into the cluster...");
        
        for (int i = 1; i <= numberOfKeys; i++) {
            String key = String.format("key:%04d", i);
            String value = sha256(key);
            
            try {
                setKey(key, value);
                insertedCount++;

                if (i % 100 == 0) {
                    System.out.println("  Progress: " + i + "/" + numberOfKeys);
                }
            } catch (Exception e) {
                System.err.println("WARN: Failed to set " + key + " - " + e.getMessage());
                failureCount++;
            }
        }

        System.out.println("=== Seed Summary ===");
        System.out.println("Total inserted : " + insertedCount);
        System.out.println("Failures       : " + failureCount);
    }

    public static void verifyData(int numberOfKeys) throws Exception {
        int verifiedCount = 0;
        int missingCount = 0;
        int mismatchedCount = 0;

        System.out.println("Verifying " + numberOfKeys + " keys...");

        for (int i = 1; i <= numberOfKeys; i++) {
            String key = String.format("key:%04d", i);
            String expectedValue = sha256(key);
            
            try {
                String actualValue = getKey(key);
                
                if (actualValue == null) {
                    missingCount++;
                } else if (!actualValue.equals(expectedValue)) {
                    mismatchedCount++;
                } else {
                    verifiedCount++;
                }
            } catch (Exception e) {
                missingCount++;
            }
        }

        System.out.println("=== Verify Summary ===");
        if (verifiedCount == numberOfKeys) {
            System.out.println("PASS — " + verifiedCount + "/" + numberOfKeys + " keys verified successfully.");
        } else {
            System.out.println("FAIL — " + missingCount + " keys missing, " + mismatchedCount + " values mismatched.");
        }
    }
}