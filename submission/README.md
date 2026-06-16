# Redis Cluster Lifecycle Tool

This project is a powerful CLI tool designed to orchestrate the provisioning, management, and zero-downtime rolling upgrades of a 6-node Redis Cluster (3 Masters + 3 Replicas). 

It acts as the "Brain" (written in Java) that directs the "Muscle" (Ansible) to execute complex operational workflows across local containerized infrastructure (Docker or Podman).

## Project Rules & Constraints Adhered To
This project was built in strict adherence to the following rules:
*   **Custom Ansible:** Written completely from scratch. No Ansible Galaxy roles or pre-built collections for Redis were used.
*   **Built-in Modules Only:** Utilizes only core Ansible modules (`command`, `shell`, `template`, `copy`, `apt`, `wait_for`, etc.).
*   **Self-Managed Infrastructure:** No managed Redis services (ElastiCache, Redis Cloud, Kubernetes operators) were used. The environment is simulated locally using containers acting as raw SSH servers.
*   **CLI Orchestrator:** The CLI tool orchestrates Ansible rather than replacing its automation capabilities.
*   **Direct TCP Data Operations:** The data seeding and verification logic uses direct TCP commands (via the Jedis library) to handle `MOVED` redirections and process 1000+ keys instantly, rather than relying on slow `redis-cli` shell executions.
*   **Container Runtime Agnostic:** Supports both Docker Engine and Podman.
*   **Platform Support:** Designed to run on macOS or Linux without assumptions about pre-installed dependencies. It includes a mandatory "Pre-flight Check" to ensure all runtimes are installed before execution.

## Project Structure
```text
├── redis-tool             # Executable bash wrapper for the CLI
├── redis-tool.jar         # Compiled Java Fat-JAR containing all logic
├── redis-tool-src/        # Java source code (Maven project)
├── ansible/               # Ansible automation code
│   ├── ansible.cfg
│   ├── inventory/hosts.ini
│   ├── playbooks/         # Playbooks for provisioning, status, and upgrading
│   └── roles/redis/       # Custom role for installing/configuring Redis
├── infra/                 # Infrastructure configuration
│   ├── Containerfile      # Ubuntu 22.04 base image with SSH server
│   └── compose.yml        # 6-node cluster definition with static IPs
└── README.md
```

## Getting Started

### 1. Prerequisites
Ensure you have the following installed on your machine:
*   Docker OR Podman
*   Ansible (v2.14+)
*   Java (JRE 17+) to execute the CLI tool

### 2. Start the Infrastructure
Spin up the 6 empty Ubuntu containers that will act as our servers:
```bash
cd infra
docker compose up -d
cd ..
```

### 3. Usage Commands
Run the tool using the provided wrapper script:

*   **Provision the Cluster** (Installs Redis 7.0.15 and forms the topology):
    ```bash
    ./redis-tool provision --version 7.0.15
    ```

*   **Seed Test Data** (Inserts 1000 deterministically hashed keys):
    ```bash
    ./redis-tool data seed --keys 1000
    ```

*   **View Cluster Status** (Displays nodes, roles, memory, and slot mapping):
    ```bash
    ./redis-tool status
    ```

*   **Perform Rolling Upgrade** (Upgrades to 7.2.6 with zero downtime via Failovers):
    ```bash
    ./redis-tool upgrade --target-version 7.2.6
    ```

*   **Verify Cluster Health** (Comprehensive 5-point post-upgrade health check):
    ```bash
    ./redis-tool verify --full
    ```

## Rolling Upgrade Strategy
The core challenge of this project is the zero-downtime rolling upgrade. The tool achieves this by:
1.  **Upgrading Replicas First:** Safely upgrading the backup nodes one-by-one.
2.  **Triggering Failovers:** Before touching a Master node, the tool issues a `CLUSTER FAILOVER` command to its corresponding Replica, promoting the newly upgraded Replica to Master status.
3.  **Upgrading Former Masters:** The old Master (now a Replica) is safely taken offline, upgraded, and rejoined to the cluster.