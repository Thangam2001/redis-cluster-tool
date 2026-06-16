package com.prabu.redistool;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AnsibleRunner {

    public static int run(String playbook, String... extraArgs) throws Exception {
        String baseDirectory = System.getProperty("user.dir");

        List<String> commandList = new ArrayList<>();
        commandList.add("ansible-playbook");
        commandList.add("-i");
        commandList.add(baseDirectory + "/ansible/inventory/hosts.ini");
        commandList.add(baseDirectory + "/ansible/playbooks/" + playbook);

        if (extraArgs != null) {
            commandList.addAll(Arrays.asList(extraArgs));
        }

        System.out.println("[Ansible] Executing command: " + String.join(" ", commandList));

        ProcessBuilder processBuilder = new ProcessBuilder(commandList);
        processBuilder.directory(new File(baseDirectory + "/ansible"));
        processBuilder.inheritIO();
        
        Process process = processBuilder.start();
        return process.waitFor();
    }
}