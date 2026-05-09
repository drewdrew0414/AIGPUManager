package com.drewdrew1.server;

import com.drewdrew1.cli.AppContext;
import com.drewdrew1.core.model.AllocationAffinity;
import com.drewdrew1.core.model.AllocationDecision;
import com.drewdrew1.core.model.AllocationDevice;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.service.EnterpriseOpsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.StreamObserver;
import io.grpc.stub.ServerCalls;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Implements the gpum.v1.GpumControl gRPC service without generated code. */
public class GpumGrpcService implements BindableService {
    static final String SERVICE = "gpum.v1.GpumControl";
    static final MethodDescriptor<String, String> HEALTH = unary("Health");
    static final MethodDescriptor<String, String> LIST_RESOURCES = unary("ListResources");
    static final MethodDescriptor<String, String> PLAN_ALLOCATION = unary("PlanAllocation");
    static final MethodDescriptor<String, String> ALLOCATE = unary("Allocate");
    static final MethodDescriptor<String, String> RELEASE = unary("Release");
    static final MethodDescriptor<String, String> SUBMIT_JOB = unary("SubmitJob");
    static final MethodDescriptor<String, String> NODE_HEARTBEAT = unary("NodeHeartbeat");
    static final MethodDescriptor<String, String> STREAM_TELEMETRY = stream("StreamTelemetry");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AppContext context;
    private final StorageHealthProbe storageHealthProbe;

    public GpumGrpcService(AppContext context, StorageHealthProbe storageHealthProbe) {
        this.context = context;
        this.storageHealthProbe = storageHealthProbe;
    }

    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(SERVICE)
                .addMethod(HEALTH, unaryCall(this::health))
                .addMethod(LIST_RESOURCES, unaryCall(this::listResources))
                .addMethod(PLAN_ALLOCATION, unaryCall(this::planAllocation))
                .addMethod(ALLOCATE, unaryCall(this::allocate))
                .addMethod(RELEASE, unaryCall(this::release))
                .addMethod(SUBMIT_JOB, unaryCall(this::submitJob))
                .addMethod(NODE_HEARTBEAT, unaryCall(this::nodeHeartbeat))
                .addMethod(STREAM_TELEMETRY, telemetryStream())
                .build();
    }

    private String health(String ignored) throws Exception {
        StorageHealthProbe.Health health = storageHealthProbe.check();
        return json(Map.of(
                "status", health.ok() ? "SERVING" : "DEGRADED",
                "version", "1.1.0",
                "storage", health.postgresStatus(),
                "cache", health.redisStatus(),
                "detail", health.detail()
        ));
    }

    private String listResources(String ignored) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodes", context.inventoryRepository().listNodes().size());
        payload.put("gpus", context.inventoryRepository().listGpus().size());
        payload.put("activeAllocations", context.allocationRepository().listActive().size());
        payload.put("opsRecords", context.opsRepository().list(null, null).size());
        return json(payload);
    }

    @SuppressWarnings("unchecked")
    private String planAllocation(String request) throws Exception {
        Map<String, Object> payload = request == null || request.isBlank()
                ? Map.of()
                : OBJECT_MAPPER.readValue(request, Map.class);
        int gpus = intValue(payload.get("gpus"), 1);
        long minVramMb = longValue(payload.get("minVramMb"), 0L);
        String modelFilter = stringValue(payload.get("modelFilter"));
        String command = "gpum alloc request --gpus " + gpus
                + (minVramMb > 0 ? " --vram " + minVramMb : "")
                + (modelFilter == null ? "" : " --model " + modelFilter)
                + " --dry-run";
        long available = context.inventoryRepository().listGpus().stream()
                .filter(gpu -> minVramMb <= 0 || (gpu.vramTotalMb() != null && gpu.vramTotalMb() >= minVramMb))
                .filter(gpu -> modelFilter == null || (gpu.model() != null && gpu.model().toLowerCase().contains(modelFilter.toLowerCase())))
                .count();
        return json(Map.of(
                "feasible", available >= gpus,
                "reason", available >= gpus ? "capacity candidate exists" : "insufficient matching GPU snapshot",
                "dryRunCommand", command
        ));
    }

    @SuppressWarnings("unchecked")
    private String allocate(String request) throws Exception {
        Map<String, Object> payload = parse(request);
        int gpus = intValue(payload.get("gpus"), 1);
        long minVramMb = longValue(payload.get("minVramMb"), 0L);
        int hours = intValue(payload.get("hours"), 24);
        Map<String, String> safetyPolicy = latestSafetyPolicy();
        int maxGpus = intValue(safetyPolicy.get("maxGpusPerRequest"), 128);
        int maxHours = intValue(safetyPolicy.get("maxLeaseHours"), 720);
        if (gpus < 1 || gpus > maxGpus) {
            return json(Map.of(
                    "allocated", false,
                    "dryRun", booleanValue(payload.get("dryRun"), false),
                    "reason", "gpus must be between 1 and " + maxGpus,
                    "requestedGpus", gpus
            ));
        }
        if (hours < 1 || hours > maxHours) {
            return json(Map.of(
                    "allocated", false,
                    "dryRun", booleanValue(payload.get("dryRun"), false),
                    "reason", "hours must be between 1 and " + maxHours,
                    "requestedGpus", gpus
            ));
        }
        if (minVramMb > 4_000_000L) {
            return json(Map.of(
                    "allocated", false,
                    "dryRun", booleanValue(payload.get("dryRun"), false),
                    "reason", "minVramMb exceeds safety ceiling of 4,000,000 MiB",
                    "requestedGpus", gpus
            ));
        }
        AllocationAffinity affinity = affinityValue(payload.get("affinity"));
        boolean dryRun = booleanValue(payload.get("dryRun"), false);
        var allocationRequest = new com.drewdrew1.core.model.AllocationRequest(
                stringValue(payload.get("owner"), "server"),
                stringValue(payload.get("tenant"), "default"),
                gpus,
                stringValue(payload.get("model")),
                minVramMb > 0 ? minVramMb : null,
                booleanValue(payload.get("exclusiveNode"), false),
                hours,
                intValue(payload.get("priority"), 5),
                booleanValue(payload.get("preemptible"), false),
                affinity,
                stringValue(payload.get("labelSelector"))
        );

        try {
            return dryRun
                    ? json(decisionPayload(context.allocationService().dryRun(allocationRequest)))
                    : json(allocationPayload(context.allocationService().allocate(allocationRequest)));
        } catch (RuntimeException e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("allocated", false);
            response.put("dryRun", dryRun);
            response.put("reason", e.getMessage());
            response.put("requestedGpus", gpus);
            return json(response);
        }
    }

    @SuppressWarnings("unchecked")
    private String release(String request) throws Exception {
        Map<String, Object> payload = parse(request);
        String id = stringValue(payload.get("id"));
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        AllocationRecord record = context.allocationService().releaseAllocation(id);
        Map<String, Object> response = allocationPayload(record);
        response.put("released", record.releasedAt() != null);
        return json(response);
    }

    @SuppressWarnings("unchecked")
    private String submitJob(String request) throws Exception {
        Map<String, Object> payload = parse(request);
        String name = stringValue(payload.get("name"), "server-job");
        String allocationId = stringValue(payload.get("allocationId"));
        String command = stringValue(payload.get("command"));
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        EnterpriseOpsService.OpsPlan plan = context.enterpriseOpsService().batchJob(
                name,
                allocationId,
                command,
                stringValue(payload.get("image")),
                stringValue(payload.get("engine"), "docker"),
                stringValue(payload.get("gpus"), "all"),
                stringValue(payload.get("shmSize"), "16g"),
                booleanValue(payload.get("execute"), false)
        );
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("recordId", plan.record().id());
        response.put("status", plan.record().status());
        response.put("name", plan.record().name());
        response.put("commands", plan.commands());
        response.put("warnings", plan.warnings());
        response.put("executed", booleanValue(payload.get("execute"), false));
        return json(response);
    }

    @SuppressWarnings("unchecked")
    private String nodeHeartbeat(String request) throws Exception {
        Map<String, Object> payload = parse(request);
        String node = stringValue(payload.get("node"));
        if (node == null) {
            throw new IllegalArgumentException("node is required");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("detail", stringValue(payload.get("detail"), ""));
        metadata.put("labels", stringValue(payload.get("labels"), ""));
        metadata.put("allocatableGpus", Integer.toString(intValue(payload.get("allocatableGpus"), 0)));
        metadata.put("observedAt", Instant.now().toString());
        var record = context.enterpriseOpsService().record(
                "server",
                "heartbeat",
                node,
                "server",
                node,
                stringValue(payload.get("status"), "ALIVE"),
                metadata
        );
        return json(Map.of(
                "node", node,
                "status", record.status(),
                "recordId", record.id(),
                "timestamp", record.createdAt().toString()
        ));
    }

    private ServerCallHandler<String, String> unaryCall(ThrowingUnary handler) {
        return ServerCalls.asyncUnaryCall((request, observer) -> {
            try {
                observer.onNext(handler.invoke(request));
                observer.onCompleted();
            } catch (Exception e) {
                observer.onError(e);
            }
        });
    }

    private ServerCallHandler<String, String> telemetryStream() {
        return ServerCalls.asyncBidiStreamingCall(responseObserver -> new StreamObserver<>() {
            @Override
            public void onNext(String request) {
                try {
                    responseObserver.onNext(json(Map.of(
                            "timestamp", Instant.now().toString(),
                            "payloadJson", context.prometheusExportService().render(),
                            "request", request == null ? "" : request
                    )));
                } catch (Exception e) {
                    responseObserver.onError(e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                responseObserver.onError(throwable);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        });
    }

    private static MethodDescriptor<String, String> unary(String name) {
        return MethodDescriptor.<String, String>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, name))
                .setRequestMarshaller(GrpcJson.MARSHALLER)
                .setResponseMarshaller(GrpcJson.MARSHALLER)
                .build();
    }

    private static MethodDescriptor<String, String> stream(String name) {
        return MethodDescriptor.<String, String>newBuilder()
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, name))
                .setRequestMarshaller(GrpcJson.MARSHALLER)
                .setResponseMarshaller(GrpcJson.MARSHALLER)
                .build();
    }

    private String json(Map<String, ?> values) throws Exception {
        return OBJECT_MAPPER.writeValueAsString(values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String request) throws Exception {
        return request == null || request.isBlank()
                ? Map.of()
                : OBJECT_MAPPER.readValue(request, Map.class);
    }

    private Map<String, Object> decisionPayload(AllocationDecision decision) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("allocated", true);
        response.put("dryRun", true);
        response.put("primaryNode", decision.primaryNodeHostname());
        response.put("expiresAt", decision.expiresAt().toString());
        response.put("devices", devices(decision.devices()));
        return response;
    }

    private Map<String, Object> allocationPayload(AllocationRecord record) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("allocated", record.status().name().equals("ACTIVE"));
        response.put("id", record.id());
        response.put("status", record.status().name());
        response.put("tenant", record.tenant());
        response.put("owner", record.owner());
        response.put("primaryNode", record.primaryNodeHostname());
        response.put("requestedGpus", record.requestedGpuCount());
        response.put("createdAt", record.createdAt().toString());
        response.put("expiresAt", record.expiresAt().toString());
        response.put("releasedAt", record.releasedAt() == null ? null : record.releasedAt().toString());
        response.put("devices", devices(record.devices()));
        return response;
    }

    private java.util.List<Map<String, Object>> devices(java.util.List<AllocationDevice> devices) {
        java.util.List<Map<String, Object>> payload = new java.util.ArrayList<>();
        for (AllocationDevice device : devices) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("node", device.nodeHostname());
            item.put("deviceId", device.deviceId());
            item.put("uuid", device.uuid());
            item.put("model", device.model());
            item.put("pciBusId", device.pciBusId());
            payload.add(item);
        }
        return payload;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return fallback;
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return fallback;
    }

    private String stringValue(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private String stringValue(Object value, String fallback) {
        String result = stringValue(value);
        return result == null ? fallback : result;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    private AllocationAffinity affinityValue(Object value) {
        String text = stringValue(value, "packed");
        return "spread".equalsIgnoreCase(text) ? AllocationAffinity.SPREAD : AllocationAffinity.PACKED;
    }

    private Map<String, String> latestSafetyPolicy() {
        var records = context.opsRepository().list("system", "safety-policy");
        return records.isEmpty() ? Map.of() : records.getFirst().metadata();
    }

    private interface ThrowingUnary {
        String invoke(String request) throws Exception;
    }
}
