package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.server.GpumGrpcClient;
import com.drewdrew1.server.GpumServer;
import com.drewdrew1.server.StorageHealthProbe;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/** Exposes the Java 21 virtual-thread gRPC server mode. */
@Command(
        name = "server",
        mixinStandardHelpOptions = true,
        description = "Java 21 virtual-thread gRPC server",
        subcommands = {
                ServerCommand.RunCommand.class,
                ServerCommand.HealthCommand.class,
                ServerCommand.ResourcesCommand.class,
                ServerCommand.AllocateCommand.class,
                ServerCommand.ReleaseCommand.class,
                ServerCommand.SubmitCommand.class,
                ServerCommand.HeartbeatCommand.class,
                ServerCommand.StorageCommand.class,
                ServerCommand.TelemetryCommand.class,
                ServerCommand.LockCommand.class
        }
)
public class ServerCommand implements Runnable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "run", description = "Run gpum gRPC server")
    static class RunCommand implements Callable<Integer> {
        @ParentCommand private ServerCommand serverCommand;
        @Option(names = "--port", defaultValue = "7070") private Integer port;
        @Option(names = "--once", description = "Start and stop immediately after readiness check") private boolean once;

        @Override public Integer call() throws Exception {
            CliSupport.requireRange(port, 0, 65535, "port");
            try (GpumServer server = new GpumServer(port, serverCommand.parent.createContext(), new StorageHealthProbe())) {
                server.start();
                System.out.printf("gpum gRPC server listening on port %d with Java virtual threads.%n", server.port());
                if (!once) {
                    server.awaitTermination();
                }
            }
            return 0;
        }
    }

    @Command(name = "health", description = "Call remote gRPC health endpoint")
    static class HealthCommand implements Callable<Integer> {
        @Option(names = "--host", defaultValue = "127.0.0.1") private String host;
        @Option(names = "--port", defaultValue = "7070") private Integer port;
        @Override public Integer call() throws Exception {
            try (GpumGrpcClient client = new GpumGrpcClient(host, port)) {
                System.out.println(client.health());
            }
            return 0;
        }
    }

    @Command(name = "resources", description = "Call remote gRPC resource summary endpoint")
    static class ResourcesCommand implements Callable<Integer> {
        @Option(names = "--host", defaultValue = "127.0.0.1") private String host;
        @Option(names = "--port", defaultValue = "7070") private Integer port;
        @Override public Integer call() throws Exception {
            try (GpumGrpcClient client = new GpumGrpcClient(host, port)) {
                System.out.println(client.listResources());
            }
            return 0;
        }
    }

    @Command(name = "allocate", description = "Create or dry-run a remote server-side GPU allocation lease")
    static class AllocateCommand implements Callable<Integer> {
        @Option(names = "--host", defaultValue = "127.0.0.1") private String host;
        @Option(names = "--port", defaultValue = "7070") private Integer port;
        @Option(names = "--owner", defaultValue = "cli") private String owner;
        @Option(names = "--tenant", defaultValue = "default") private String tenant;
        @Option(names = "--gpus", defaultValue = "1") private Integer gpus;
        @Option(names = "--model") private String model;
        @Option(names = "--vram", description = "Minimum VRAM in MiB") private Long minVramMb;
        @Option(names = "--hours", defaultValue = "24") private Integer hours;
        @Option(names = "--priority", defaultValue = "5") private Integer priority;
        @Option(names = "--affinity", defaultValue = "packed", description = "packed or spread") private String affinity;
        @Option(names = "--label-selector", description = "Required node labels, key=value[,key=value]") private String labelSelector;
        @Option(names = "--exclusive-node") private boolean exclusiveNode;
        @Option(names = "--preemptible") private boolean preemptible;
        @Option(names = "--dry-run") private boolean dryRun;

        @Override public Integer call() throws Exception {
            CliSupport.requireRange(port, 0, 65535, "port");
            CliSupport.requirePositive(gpus, "gpus");
            CliSupport.requirePositive(hours, "hours");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("owner", owner);
            payload.put("tenant", tenant);
            payload.put("gpus", gpus);
            payload.put("model", model);
            payload.put("minVramMb", minVramMb);
            payload.put("hours", hours);
            payload.put("priority", priority);
            payload.put("affinity", affinity);
            payload.put("labelSelector", labelSelector);
            payload.put("exclusiveNode", exclusiveNode);
            payload.put("preemptible", preemptible);
            payload.put("dryRun", dryRun);
            try (GpumGrpcClient client = new GpumGrpcClient(host, port)) {
                System.out.println(client.allocate(json(payload)));
            }
            return 0;
        }
    }

    @Command(name = "release", description = "Release a remote server-side allocation lease")
    static class ReleaseCommand implements Callable<Integer> {
        @Option(names = "--host", defaultValue = "127.0.0.1") private String host;
        @Option(names = "--port", defaultValue = "7070") private Integer port;
        @Option(names = "--id", required = true) private String id;

        @Override public Integer call() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            try (GpumGrpcClient client = new GpumGrpcClient(host, port)) {
                System.out.println(client.release(json(payload)));
            }
            return 0;
        }
    }

    @Command(name = "submit", description = "Submit a remote batch execution plan bound to an allocation")
    static class SubmitCommand implements Callable<Integer> {
        @Option(names = "--host", defaultValue = "127.0.0.1") private String host;
        @Option(names = "--port", defaultValue = "7070") private Integer port;
        @Option(names = "--name", defaultValue = "server-job") private String name;
        @Option(names = "--allocation-id") private String allocationId;
        @Option(names = "--command", required = true) private String command;
        @Option(names = "--image") private String image;
        @Option(names = "--engine", defaultValue = "docker") private String engine;
        @Option(names = "--gpus", defaultValue = "all") private String gpus;
        @Option(names = "--shm-size", defaultValue = "16g") private String shmSize;
        @Option(names = "--execute") private boolean execute;

        @Override public Integer call() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", name);
            payload.put("allocationId", allocationId);
            payload.put("command", command);
            payload.put("image", image);
            payload.put("engine", engine);
            payload.put("gpus", gpus);
            payload.put("shmSize", shmSize);
            payload.put("execute", execute);
            try (GpumGrpcClient client = new GpumGrpcClient(host, port)) {
                System.out.println(client.submitJob(json(payload)));
            }
            return 0;
        }
    }

    @Command(name = "heartbeat", description = "Send a node heartbeat event to the remote server")
    static class HeartbeatCommand implements Callable<Integer> {
        @Option(names = "--host", defaultValue = "127.0.0.1") private String host;
        @Option(names = "--port", defaultValue = "7070") private Integer port;
        @Option(names = "--node", required = true) private String node;
        @Option(names = "--status", defaultValue = "ALIVE") private String status;
        @Option(names = "--detail") private String detail;
        @Option(names = "--labels") private String labels;
        @Option(names = "--allocatable-gpus", defaultValue = "0") private Integer allocatableGpus;

        @Override public Integer call() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("node", node);
            payload.put("status", status);
            payload.put("detail", detail);
            payload.put("labels", labels);
            payload.put("allocatableGpus", allocatableGpus);
            try (GpumGrpcClient client = new GpumGrpcClient(host, port)) {
                System.out.println(client.nodeHeartbeat(json(payload)));
            }
            return 0;
        }
    }

    @Command(name = "telemetry", description = "Read one event from the gRPC bidirectional telemetry stream")
    static class TelemetryCommand implements Callable<Integer> {
        @Option(names = "--host", defaultValue = "127.0.0.1") private String host;
        @Option(names = "--port", defaultValue = "7070") private Integer port;
        @Option(names = "--filter") private String filter;
        @Override public Integer call() throws Exception {
            try (GpumGrpcClient client = new GpumGrpcClient(host, port)) {
                System.out.println(client.telemetryOnce(filter));
            }
            return 0;
        }
    }

    @Command(name = "lock", description = "Acquire or release Redis distributed allocation lock")
    static class LockCommand implements Callable<Integer> {
        @Option(names = "--key", required = true) private String key;
        @Option(names = "--owner", defaultValue = "cli") private String owner;
        @Option(names = "--ttl-ms", defaultValue = "30000") private Long ttlMs;
        @Option(names = "--release") private boolean release;
        @Override public Integer call() {
            StorageHealthProbe probe = new StorageHealthProbe();
            StorageHealthProbe.LockResult result = release
                    ? probe.releaseRedisLock(key, owner)
                    : probe.acquireRedisLock(key, owner, ttlMs);
            System.out.printf("lock=%s detail=%s%n", result.acquired(), result.detail());
            return result.acquired() ? 0 : 1;
        }
    }

    @Command(name = "storage", description = "Check optional PostgreSQL and Redis backend connectivity")
    static class StorageCommand implements Callable<Integer> {
        @Override public Integer call() {
            StorageHealthProbe.Health health = new StorageHealthProbe().check();
            System.out.println("PostgreSQL: " + health.postgresStatus());
            System.out.println("Redis: " + health.redisStatus());
            System.out.println("Detail: " + health.detail());
            return health.ok() ? 0 : 1;
        }
    }

    private static String json(Map<String, Object> payload) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(payload);
    }
}
