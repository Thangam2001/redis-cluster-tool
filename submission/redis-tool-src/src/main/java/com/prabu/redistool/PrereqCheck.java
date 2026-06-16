package com.prabu.redistool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;


public class PrereqCheck {

    public static boolean run() {
        boolean isEnvironmentReady = true;

        String dockerVersion = getCommandVersion("docker", "--version");
        String ansibleVersion = getCommandVersion("ansible-playbook", "--version");

        if (dockerVersion != null) {
            System.out.println("✓ Container runtime found: " + dockerVersion);
        } else {
            System.err.println("✗ Container runtime not found (Docker or Podman is required)");
            System.err.println("  To install Docker, visit: https://docs.docker.com/engine/install/");
            System.err.println("  To install Podman, visit: https://podman.io/docs/installation");
            isEnvironmentReady = false;
        }

        if (ansibleVersion != null) {
            System.out.println("✓ Ansible found: " + ansibleVersion);
        } else {
            System.err.println("✗ Ansible not found");
            System.err.println("  To install, run: pip install ansible");
            isEnvironmentReady = false;
        }

        if (isEnvironmentReady) {
            System.out.println("Prerequisite check passed. Proceeding...\n");
        } else {
            System.err.println("\nPlease install the missing dependencies and retry.");
        }

        return isEnvironmentReady;
    }

    private static String getCommandVersion(String... command) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String firstLine = reader.readLine();

                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                if (finished && process.exitValue() == 0) {
                    return firstLine;
                }
            }
        } catch (Exception e) {
            // Command is missing or failed to execute, return null
        }
        return null;
    }
}