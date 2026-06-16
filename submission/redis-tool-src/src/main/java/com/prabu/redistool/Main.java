package com.prabu.redistool;

import com.prabu.redistool.commands.*;

public class Main {
    
    public static void main(String[] args) throws Exception {
        if (!PrereqCheck.run()) {
            System.exit(1);
        }

        if (args.length == 0) {
            printUsageAndExit();
        }

        String command = args[0];
        
        switch (command) {
            case "provision":
                ProvisionCommand.run(args);
                break;
            case "data":
                DataCommand.run(args);
                break;
            case "status":
                StatusCommand.run(args);
                break;
            case "upgrade":
                UpgradeCommand.run(args);
                break;
            case "verify":
                VerifyCommand.run(args);
                break;
            default:
                System.err.println("Error: Unknown command '" + command + "'");
                printUsageAndExit();
        }
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: redis-tool <command> [options]");
        System.out.println("Available commands:");
        System.out.println("  provision    - Install and configure a new Redis cluster");
        System.out.println("  data         - Seed or verify test data in the cluster");
        System.out.println("  status       - Display the current health and topology of the cluster");
        System.out.println("  upgrade      - Perform a zero-downtime rolling upgrade");
        System.out.println("  verify       - Perform a full cluster health and data integrity check");
        System.exit(1);
    }
}