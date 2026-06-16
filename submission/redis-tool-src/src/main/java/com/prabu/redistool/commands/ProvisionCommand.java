package com.prabu.redistool.commands;

import com.prabu.redistool.AnsibleRunner;

public class ProvisionCommand {

    private static final String DEFAULT_REDIS_VERSION = "7.0.15";

    public static void run(String[] args) throws Exception {
        String targetVersion = DEFAULT_REDIS_VERSION;

        for (int i = 0; i < args.length; i++) {
            if ("--version".equals(args[i]) && i + 1 < args.length) {
                targetVersion = args[i + 1];
            }
        }

        System.out.println("=== Provisioning Redis Cluster v" + targetVersion + " ===");

        int exitCode = AnsibleRunner.run("provision.yml", "--extra-vars", "redis_version=" + targetVersion);
        
        if (exitCode != 0) {
            System.err.println("ERROR: Provisioning failed with exit code " + exitCode);
            System.exit(exitCode);
        }

        System.out.println("\nProvisioning completed successfully.");
        StatusCommand.run(new String[]{"status"});
    }
}