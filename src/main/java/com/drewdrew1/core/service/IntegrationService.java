package com.drewdrew1.core.service;

import com.drewdrew1.core.config.BentoMlConfig;
import com.drewdrew1.core.config.ExternalToolConfig;
import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.config.KubernetesConfig;
import com.drewdrew1.core.config.MlflowConfig;
import com.drewdrew1.core.config.ToolConfig;
import com.drewdrew1.core.model.AllocationDevice;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationStatus;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Wraps configured external integrations such as kubectl, MLflow, BentoML, AI launchers, and custom tools. */
public class IntegrationService {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<LinkedHashMap<String, Object>> YAML_MAP = new TypeReference<>() {};

    private final CommandExecutor commandExecutor;
    private final GpumConfig config;

    public IntegrationService(CommandExecutor commandExecutor, GpumConfig config) {
        this.commandExecutor = commandExecutor;
        this.config = config;
    }

    public CommandResult kubectl(List<String> args) {
        ToolConfig tools = config.getTools();
        List<String> command = new ArrayList<>();
        command.add(tools.getKubectl());
        KubernetesConfig kubernetes = config.getKubernetes();
        if (kubernetes.getContext() != null && !kubernetes.getContext().isBlank()) {
            command.add("--context");
            command.add(kubernetes.getContext());
        }
        command.addAll(args);
        return commandExecutor.execute(command);
    }

    public CommandResult mlflow(List<String> args) {
        ToolConfig tools = config.getTools();
        MlflowConfig mlflow = config.getMlflow();
        List<String> command = new ArrayList<>();
        command.add(tools.getMlflow());
        if (mlflow.getTrackingUri() != null && !mlflow.getTrackingUri().isBlank()) {
            command.add("--tracking-uri");
            command.add(mlflow.getTrackingUri());
        }
        if (mlflow.getRegistryUri() != null && !mlflow.getRegistryUri().isBlank()) {
            command.add("--registry-uri");
            command.add(mlflow.getRegistryUri());
        }
        if (mlflow.getProfile() != null && !mlflow.getProfile().isBlank()) {
            command.add("--profile");
            command.add(mlflow.getProfile());
        }
        command.addAll(args);
        return commandExecutor.execute(command);
    }

    public CommandResult bentoml(List<String> args) {
        ToolConfig tools = config.getTools();
        BentoMlConfig bentoml = config.getBentoml();
        List<String> command = new ArrayList<>();
        command.add(tools.getBentoml());
        if (bentoml.getHome() != null && !bentoml.getHome().isBlank()) {
            command.add("--working-dir");
            command.add(bentoml.getHome());
        }
        command.addAll(args);
        return commandExecutor.execute(command);
    }

    public CommandResult externalTool(String name, List<String> args) {
        Map<String, ExternalToolConfig> externalTools = config.getExternalTools();
        ExternalToolConfig tool = externalTools.get(name);
        if (tool == null || !tool.isEnabled()) {
            throw new IllegalArgumentException("External tool is not configured or disabled: " + name);
        }
        if (tool.getCommand() == null || tool.getCommand().isBlank()) {
            throw new IllegalArgumentException("External tool command is blank: " + name);
        }
        List<String> command = new ArrayList<>();
        command.add(tool.getCommand());
        command.addAll(tool.getDefaultArgs());
        command.addAll(args);
        return commandExecutor.execute(command);
    }

    public Map<String, String> allocationEnvironment(AllocationRecord allocation) {
        Map<String, String> env = new LinkedHashMap<>();
        List<String> hostnames = allocation.devices().stream()
                .map(AllocationDevice::nodeHostname)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        String primaryNode = allocation.primaryNodeHostname() == null || allocation.primaryNodeHostname().isBlank()
                ? (hostnames.isEmpty() ? "" : hostnames.getFirst())
                : allocation.primaryNodeHostname();

        env.put("GPUM_ALLOCATION_ID", allocation.id());
        env.put("GPUM_PRIMARY_NODE", primaryNode);
        env.put("GPUM_NODE_HOSTS", String.join(",", hostnames));
        env.put("GPUM_NODE_COUNT", Integer.toString(Math.max(1, hostnames.size())));
        env.put("GPUM_GPU_COUNT", Integer.toString(allocation.devices().size()));
        env.put("GPUM_GPU_MODELS", allocation.devices().stream()
                .map(device -> device.model() == null ? "unknown" : device.model())
                .distinct()
                .collect(Collectors.joining(",")));
        env.put("GPUM_GPU_UUIDS", allocation.devices().stream()
                .map(device -> device.uuid() == null ? "" : device.uuid())
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(",")));
        if (!primaryNode.isBlank()) {
            env.put("MASTER_ADDR", primaryNode);
            env.put("GPUM_MASTER_ADDR", primaryNode);
            env.put("GPUM_RDZV_ENDPOINT", primaryNode + ":29500");
        }

        populateVendorVisibility(allocation, env);
        MlflowConfig mlflow = config.getMlflow();
        if (mlflow.getTrackingUri() != null && !mlflow.getTrackingUri().isBlank()) {
            env.put("MLFLOW_TRACKING_URI", mlflow.getTrackingUri());
        }
        if (mlflow.getRegistryUri() != null && !mlflow.getRegistryUri().isBlank()) {
            env.put("MLFLOW_REGISTRY_URI", mlflow.getRegistryUri());
        }
        if (mlflow.getExperiment() != null && !mlflow.getExperiment().isBlank()) {
            env.put("MLFLOW_EXPERIMENT_NAME", mlflow.getExperiment());
        }
        return env;
    }

    public AiLaunchPlan aiLaunchPlan(AllocationRecord allocation, String tool, List<String> args, boolean execute) {
        return aiLaunchPlan(allocation, tool, args, execute, false, null);
    }

    public AiLaunchPlan aiLaunchPlan(
            AllocationRecord allocation,
            String tool,
            List<String> args,
            boolean execute,
            boolean viaSsh,
            String sshUser
    ) {
        String executable = resolveAiToolExecutable(tool);
        Map<String, String> env = allocationEnvironment(allocation);
        List<String> baseCommand = new ArrayList<>();
        baseCommand.add(executable);
        baseCommand.addAll(args);
        List<String> shellCommand = viaSsh
                ? wrapWithSshEnvironment(allocation, sshUser, env, baseCommand)
                : wrapWithEnvironment(env, baseCommand);
        if (!execute) {
            return new AiLaunchPlan(tool, env, baseCommand, shellCommand, null);
        }
        CommandResult result = commandExecutor.execute(shellCommand);
        return new AiLaunchPlan(tool, env, baseCommand, shellCommand, result);
    }

    public AiPresetPlan aiPresetPlan(
            String preset,
            AllocationRecord allocation,
            String entrypoint,
            List<String> extraArgs
    ) {
        Map<String, String> env = allocationEnvironment(allocation);
        List<String> hosts = allocation.devices().stream()
                .map(AllocationDevice::nodeHostname)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        Map<String, Long> gpusByNode = allocation.devices().stream()
                .collect(Collectors.groupingBy(AllocationDevice::nodeHostname, LinkedHashMap::new, Collectors.counting()));

        int nnodes = Math.max(1, hosts.size());
        int totalGpus = allocation.devices().size();
        int localGpus = gpusByNode.isEmpty() ? totalGpus : gpusByNode.values().iterator().next().intValue();
        String safeEntrypoint = entrypoint == null ? "" : entrypoint.trim();

        return switch (preset.toLowerCase(Locale.ROOT)) {
            case "torchrun-ddp" -> new AiPresetPlan(
                    "torchrun",
                    mergeArgs(List.of(
                            "--nnodes", Integer.toString(nnodes),
                            "--nproc-per-node", Integer.toString(localGpus),
                            "--rdzv-backend", "c10d",
                            "--rdzv-endpoint", env.getOrDefault("GPUM_RDZV_ENDPOINT", "localhost:29500"),
                            safeEntrypoint
                    ), extraArgs),
                    null,
                    List.of("Distributed PyTorch launch using torchrun.")
            );
            case "accelerate" -> new AiPresetPlan(
                    "accelerate",
                    mergeArgs(List.of(
                            "launch",
                            "--num_processes", Integer.toString(totalGpus),
                            "--num_machines", Integer.toString(nnodes),
                            safeEntrypoint
                    ), extraArgs),
                    null,
                    List.of("Accelerate launch preset using allocation GPU count.")
            );
            case "deepspeed" -> new AiPresetPlan(
                    "deepspeed",
                    mergeArgs(List.of(
                            "--num_nodes", Integer.toString(nnodes),
                            "--num_gpus", Integer.toString(localGpus),
                            safeEntrypoint
                    ), extraArgs),
                    null,
                    List.of("DeepSpeed launch preset using allocation topology.")
            );
            case "vllm-serve" -> new AiPresetPlan(
                    "vllm",
                    mergeArgs(List.of(
                            "serve",
                            safeEntrypoint,
                            "--tensor-parallel-size", Integer.toString(totalGpus)
                    ), extraArgs),
                    null,
                    List.of("vLLM serve preset using allocation GPU count as tensor parallel size.")
            );
            case "slurm-sbatch" -> new AiPresetPlan(
                    "sbatch",
                    List.of(),
                    renderSlurmScript(allocation, safeEntrypoint, extraArgs, nnodes, localGpus, env),
                    List.of("Generated sbatch script. Review cluster-specific partition/account directives before execution.")
            );
            case "ray-job" -> new AiPresetPlan(
                    "ray",
                    List.of(),
                    renderRayCommand(allocation, safeEntrypoint, extraArgs, env),
                    List.of("Generated Ray job command. Ensure the Ray cluster is already available.")
            );
            default -> throw new IllegalArgumentException("Unknown AI preset: " + preset);
        };
    }

    public List<String> loadArgumentTemplate(Path path, Map<String, String> variables) {
        requireRegularTemplateFile(path);
        try {
            List<String> args = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                args.add(renderTemplate(trimmed, variables));
            }
            if (args.isEmpty()) {
                throw new IllegalArgumentException("Template file produced no arguments: " + path);
            }
            return args;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load launch template: " + path, e);
        }
    }

    public KubernetesSubmitPlan kubernetesSubmitPlan(KubernetesSubmitRequest request) {
        validateKubernetesSubmitRequest(request);
        int gpuCount = resolveGpuCount(request.gpus(), request.allocation());
        String namespace = request.namespace() == null || request.namespace().isBlank()
                ? config.getKubernetes().getNamespace()
                : request.namespace();
        String kind = request.kind() == null || request.kind().isBlank() ? "Job" : request.kind();

        String manifest = request.templatePath() == null
                ? buildKubernetesManifest(request.name(), request.image(), namespace, kind, request.schedule())
                : renderKubernetesTemplate(request.templatePath(), request, namespace);
        manifest = patchKubernetesManifest(manifest, gpuCount, namespace, request.allocation(), request);

        if (!request.execute()) {
            return new KubernetesSubmitPlan(namespace, manifest, null, null, List.of("preview-only"));
        }

        Path tempManifest = null;
        List<String> events = new ArrayList<>();
        CommandResult lastResult = null;
        try {
            tempManifest = Files.createTempFile("gpum-k8s-submit-", ".yaml");
            Files.writeString(tempManifest, manifest, StandardCharsets.UTF_8);
            int attempts = Math.max(1, request.retryCount() + 1);
            for (int attempt = 1; attempt <= attempts; attempt++) {
                events.add("apply-attempt=" + attempt);
                lastResult = kubectl(List.of("apply", "-f", tempManifest.toAbsolutePath().toString()));
                if (!lastResult.isSuccess()) {
                    events.add("apply-failed=" + safeError(lastResult));
                    if (attempt >= attempts) {
                        if (request.rollbackOnFail()) {
                            deleteKubernetesResource(kind, request.name(), namespace, events);
                        }
                        return new KubernetesSubmitPlan(namespace, manifest, tempManifest, lastResult, events);
                    }
                    continue;
                }

                if (request.watchSeconds() > 0) {
                    boolean ready = watchKubernetesResource(kind, request.name(), namespace, request.watchSeconds(), events);
                    if (!ready) {
                        if (request.rollbackOnFail()) {
                            deleteKubernetesResource(kind, request.name(), namespace, events);
                        }
                        if (attempt >= attempts) {
                            return new KubernetesSubmitPlan(namespace, manifest, tempManifest, lastResult, events);
                        }
                        continue;
                    }
                }
                return new KubernetesSubmitPlan(namespace, manifest, tempManifest, lastResult, events);
            }
            return new KubernetesSubmitPlan(namespace, manifest, tempManifest, lastResult, events);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply Kubernetes manifest", e);
        } finally {
            if (tempManifest != null) {
                try {
                    Files.deleteIfExists(tempManifest);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void populateVendorVisibility(AllocationRecord allocation, Map<String, String> env) {
        List<AllocationDevice> devices = allocation.devices();
        if (devices.isEmpty()) {
            return;
        }
        boolean allNvidia = devices.stream().allMatch(device -> device.vendor() == GpuVendor.NVIDIA);
        boolean allAmd = devices.stream().allMatch(device -> device.vendor() == GpuVendor.AMD);
        boolean allIntel = devices.stream().allMatch(device -> device.vendor() == GpuVendor.INTEL);
        String ids = devices.stream()
                .map(AllocationDevice::deviceId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(","));
        if (allNvidia) {
            env.put("CUDA_VISIBLE_DEVICES", ids);
            env.put("NVIDIA_VISIBLE_DEVICES", ids);
        } else if (allAmd) {
            env.put("ROCR_VISIBLE_DEVICES", ids);
            env.put("HIP_VISIBLE_DEVICES", ids);
        } else if (allIntel) {
            env.put("ZE_AFFINITY_MASK", ids);
            env.put("ONEAPI_DEVICE_SELECTOR", "level_zero:" + ids);
        }
    }

    private List<String> wrapWithEnvironment(Map<String, String> env, List<String> baseCommand) {
        ToolConfig tools = config.getTools();
        if (isWindows()) {
            StringBuilder script = new StringBuilder();
            for (Map.Entry<String, String> entry : env.entrySet()) {
                script.append("set ").append(entry.getKey()).append('=').append(escapeCmdValue(entry.getValue())).append("&& ");
            }
            script.append(baseCommand.stream().map(this::escapeCmdArg).collect(Collectors.joining(" ")));
            return List.of(tools.getCmd(), "/c", script.toString());
        }
        StringBuilder shell = new StringBuilder();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            shell.append(entry.getKey()).append('=').append(singleQuote(entry.getValue())).append(' ');
        }
        shell.append(baseCommand.stream().map(this::singleQuote).collect(Collectors.joining(" ")));
        return List.of(tools.getBash(), "-lc", shell.toString());
    }

    private List<String> wrapWithSshEnvironment(
            AllocationRecord allocation,
            String sshUser,
            Map<String, String> env,
            List<String> baseCommand
    ) {
        String host = allocation.primaryNodeHostname();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Allocation has no primary node for SSH launch.");
        }
        String user = sshUser == null || sshUser.isBlank() ? System.getProperty("user.name", "unknown") : sshUser;
        StringBuilder shell = new StringBuilder();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            shell.append(entry.getKey()).append('=').append(singleQuote(entry.getValue())).append(' ');
        }
        shell.append(baseCommand.stream().map(this::singleQuote).collect(Collectors.joining(" ")));
        return List.of(config.getTools().getSsh(), user + "@" + host, shell.toString());
    }

    private String resolveAiToolExecutable(String tool) {
        return switch (tool.toLowerCase(Locale.ROOT)) {
            case "python", "torchrun", "accelerate", "deepspeed", "vllm" -> tool;
            default -> throw new IllegalArgumentException("Unsupported AI tool. Use one of: python, torchrun, accelerate, deepspeed, vllm");
        };
    }

    private int resolveGpuCount(Integer gpus, AllocationRecord allocation) {
        if (gpus != null && gpus <= 0) {
            throw new IllegalArgumentException("gpus must be > 0");
        }
        if (allocation == null) {
            return gpus == null ? 1 : gpus;
        }
        int allocationGpuCount = allocation.devices().size();
        if (gpus != null && gpus != allocationGpuCount) {
            throw new IllegalArgumentException("Requested gpus does not match allocation GPU count (" + allocationGpuCount + ")");
        }
        return allocationGpuCount;
    }

    private String patchKubernetesManifest(
            String yaml,
            int gpuCount,
            String namespace,
            AllocationRecord allocation,
            KubernetesSubmitRequest request
    ) {
        try {
            LinkedHashMap<String, Object> root = YAML.readValue(yaml, YAML_MAP);
            Map<String, Object> metadata = map(root, "metadata");
            metadata.put("namespace", namespace);
            Map<String, Object> labels = map(metadata, "labels");
            labels.put("managed-by", "gpum");
            if (allocation != null) {
                labels.put("gpum_allocation", allocation.id());
            }

            Map<String, Object> spec = map(root, "spec");
            Map<String, Object> templateRoot = "CronJob".equalsIgnoreCase(String.valueOf(root.get("kind")))
                    ? map(map(spec, "jobTemplate"), "spec")
                    : spec;
            Map<String, Object> template = map(templateRoot, "template");
            Map<String, Object> templateMetadata = map(template, "metadata");
            Map<String, Object> templateLabels = map(templateMetadata, "labels");
            templateLabels.putAll(labels);

            Map<String, Object> podSpec = map(template, "spec");
            KubernetesConfig kubernetes = config.getKubernetes();
            if (kubernetes.getServiceAccount() != null && !kubernetes.getServiceAccount().isBlank()) {
                podSpec.put("serviceAccountName", kubernetes.getServiceAccount());
            }
            if (!"Deployment".equalsIgnoreCase(String.valueOf(root.get("kind")))) {
                podSpec.put("restartPolicy", "Never");
            }

            List<Map<String, Object>> containers = containerList(podSpec);
            if (containers.isEmpty()) {
                throw new IllegalArgumentException("Generated Kubernetes manifest contains no containers.");
            }
            Map<String, Object> container = containers.getFirst();
            if (kubernetes.getImagePullPolicy() != null && !kubernetes.getImagePullPolicy().isBlank()) {
                container.put("imagePullPolicy", kubernetes.getImagePullPolicy());
            }

            if (allocation != null) {
                appendEnv(container, allocationEnvironment(allocation));
                pinAllocationNode(podSpec, allocation);
                applyGpuResources(container, gpuCount, allocation);
            } else {
                applyGenericGpuResources(container, gpuCount);
            }
            appendEnv(container, parseInlineEnv(request.envPairs()));
            appendSecretEnv(container, request.secretEnvPairs());
            appendVolumesAndMounts(podSpec, container, request.datasetPvc(), request.datasetMountPath(), request.pvcMounts());
            return YAML.writeValueAsString(root);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to patch Kubernetes manifest", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> existing) {
            return (Map<String, Object>) existing;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> containerList(Map<String, Object> podSpec) {
        Object value = podSpec.get("containers");
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        podSpec.put("containers", created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private void appendEnv(Map<String, Object> container, Map<String, String> env) {
        Object envValue = container.get("env");
        List<Map<String, Object>> entries;
        if (envValue instanceof List<?> list) {
            entries = (List<Map<String, Object>>) list;
        } else {
            entries = new ArrayList<>();
            container.put("env", entries);
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getKey());
            item.put("value", Objects.toString(entry.getValue(), ""));
            entries.add(item);
        }
    }

    @SuppressWarnings("unchecked")
    private void appendSecretEnv(Map<String, Object> container, List<String> secretEnvPairs) {
        if (secretEnvPairs == null || secretEnvPairs.isEmpty()) {
            return;
        }
        Object envValue = container.get("env");
        List<Map<String, Object>> entries;
        if (envValue instanceof List<?> list) {
            entries = (List<Map<String, Object>>) list;
        } else {
            entries = new ArrayList<>();
            container.put("env", entries);
        }
        for (String pair : secretEnvPairs) {
            String[] leftRight = pair.split("=", 2);
            if (leftRight.length != 2) {
                throw new IllegalArgumentException("secret-env must be KEY=secret:key");
            }
            String[] secretKey = leftRight[1].split(":", 2);
            if (secretKey.length != 2) {
                throw new IllegalArgumentException("secret-env must be KEY=secret:key");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", leftRight[0]);
            Map<String, Object> valueFrom = new LinkedHashMap<>();
            Map<String, Object> secretRef = new LinkedHashMap<>();
            secretRef.put("name", secretKey[0]);
            secretRef.put("key", secretKey[1]);
            valueFrom.put("secretKeyRef", secretRef);
            entry.put("valueFrom", valueFrom);
            entries.add(entry);
        }
    }

    @SuppressWarnings("unchecked")
    private void appendVolumesAndMounts(
            Map<String, Object> podSpec,
            Map<String, Object> container,
            String datasetPvc,
            String datasetMountPath,
            List<String> pvcMounts
    ) {
        List<String> mounts = new ArrayList<>();
        if (datasetPvc != null && !datasetPvc.isBlank()) {
            mounts.add("dataset=" + datasetPvc + ":" + ((datasetMountPath == null || datasetMountPath.isBlank()) ? "/datasets" : datasetMountPath) + ":ro");
        }
        if (pvcMounts != null) {
            mounts.addAll(pvcMounts);
        }
        if (mounts.isEmpty()) {
            return;
        }

        List<Map<String, Object>> volumes;
        Object volumeValue = podSpec.get("volumes");
        if (volumeValue instanceof List<?> list) {
            volumes = (List<Map<String, Object>>) list;
        } else {
            volumes = new ArrayList<>();
            podSpec.put("volumes", volumes);
        }

        List<Map<String, Object>> volumeMounts;
        Object mountValue = container.get("volumeMounts");
        if (mountValue instanceof List<?> list) {
            volumeMounts = (List<Map<String, Object>>) list;
        } else {
            volumeMounts = new ArrayList<>();
            container.put("volumeMounts", volumeMounts);
        }

        for (String raw : mounts) {
            String[] parts = raw.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("mount-pvc must be name=claim:/path[:ro]");
            }
            String volumeName = parts[0].trim();
            String[] claimAndPath = parts[1].split(":", 3);
            if (claimAndPath.length < 2) {
                throw new IllegalArgumentException("mount-pvc must be name=claim:/path[:ro]");
            }
            String claimName = claimAndPath[0].trim();
            String mountPath = claimAndPath[1].trim();
            boolean readOnly = claimAndPath.length == 3 && "ro".equalsIgnoreCase(claimAndPath[2].trim());
            Map<String, Object> volume = new LinkedHashMap<>();
            volume.put("name", volumeName);
            Map<String, Object> pvc = new LinkedHashMap<>();
            pvc.put("claimName", claimName);
            volume.put("persistentVolumeClaim", pvc);
            volumes.add(volume);

            Map<String, Object> volumeMount = new LinkedHashMap<>();
            volumeMount.put("name", volumeName);
            volumeMount.put("mountPath", mountPath);
            if (readOnly) {
                volumeMount.put("readOnly", true);
            }
            volumeMounts.add(volumeMount);
        }
    }

    private void pinAllocationNode(Map<String, Object> podSpec, AllocationRecord allocation) {
        if (allocation.primaryNodeHostname() == null || allocation.primaryNodeHostname().isBlank()) {
            return;
        }
        Map<String, Object> nodeSelector = map(podSpec, "nodeSelector");
        nodeSelector.put("kubernetes.io/hostname", allocation.primaryNodeHostname());
    }

    private void applyGpuResources(Map<String, Object> container, int gpuCount, AllocationRecord allocation) {
        String key = resolveGpuResourceKey(allocation);
        applyGpuResourceKey(container, key, gpuCount);
    }

    private void applyGenericGpuResources(Map<String, Object> container, int gpuCount) {
        String key = config.getKubernetes().getGpuResourceKey();
        if (key == null || key.isBlank()) {
            key = "nvidia.com/gpu";
        }
        applyGpuResourceKey(container, key, gpuCount);
    }

    private void applyGpuResourceKey(Map<String, Object> container, String key, int gpuCount) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("GPU resource key is blank. Set kubernetes.gpuResourceKey in config.");
        }
        Map<String, Object> resources = map(container, "resources");
        Map<String, Object> limits = map(resources, "limits");
        Map<String, Object> requests = map(resources, "requests");
        limits.put(key, gpuCount);
        requests.put(key, gpuCount);
    }

    private String resolveGpuResourceKey(AllocationRecord allocation) {
        String configured = config.getKubernetes().getGpuResourceKey();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        boolean allNvidia = allocation.devices().stream().allMatch(device -> device.vendor() == GpuVendor.NVIDIA);
        boolean allAmd = allocation.devices().stream().allMatch(device -> device.vendor() == GpuVendor.AMD);
        boolean allIntel = allocation.devices().stream().allMatch(device -> device.vendor() == GpuVendor.INTEL);
        if (allNvidia) {
            return "nvidia.com/gpu";
        }
        if (allAmd) {
            return "amd.com/gpu";
        }
        if (allIntel) {
            return "gpu.intel.com/i915";
        }
        throw new IllegalArgumentException("Mixed-vendor allocation cannot infer a single Kubernetes GPU resource key.");
    }

    private void requireRegularTemplateFile(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("template path must not be null");
        }
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Template file not found: " + path);
        }
        try {
            if (Files.size(path) > 1024 * 1024) {
                throw new IllegalArgumentException("Template file is too large: " + path);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to inspect template file: " + path, e);
        }
    }

    private String renderTemplate(String input, Map<String, String> variables) {
        String rendered = input;
        int start = rendered.indexOf("{{");
        while (start >= 0) {
            int end = rendered.indexOf("}}", start + 2);
            if (end < 0) {
                throw new IllegalArgumentException("Unclosed template token: " + input);
            }
            String key = rendered.substring(start + 2, end).trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Empty template token: " + input);
            }
            String value = variables.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Unknown template token {{" + key + "}} in: " + input);
            }
            rendered = rendered.substring(0, start) + value + rendered.substring(end + 2);
            start = rendered.indexOf("{{");
        }
        return rendered;
    }

    private String renderKubernetesTemplate(Path templatePath, KubernetesSubmitRequest request, String namespace) {
        requireRegularTemplateFile(templatePath);
        try {
            Map<String, String> variables = new LinkedHashMap<>();
            variables.put("NAME", request.name());
            variables.put("IMAGE", request.image() == null ? "" : request.image());
            variables.put("NAMESPACE", namespace);
            variables.put("KIND", request.kind() == null ? "Job" : request.kind());
            variables.put("SCHEDULE", request.schedule() == null ? "" : request.schedule());
            if (request.allocation() != null) {
                variables.putAll(allocationEnvironment(request.allocation()));
            }
            String source = Files.readString(templatePath, StandardCharsets.UTF_8);
            return renderTemplate(source, variables);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to render Kubernetes template: " + templatePath, e);
        }
    }

    private String buildKubernetesManifest(String name, String image, String namespace, String kind, String schedule) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("apiVersion", "Deployment".equalsIgnoreCase(kind) ? "apps/v1" : "batch/v1");
        root.put("kind", kind);
        root.put("metadata", new LinkedHashMap<>(Map.of("name", name, "namespace", namespace)));

        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", name);
        container.put("image", image);
        List<Map<String, Object>> containers = new ArrayList<>();
        containers.add(container);

        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("containers", containers);
        podSpec.put("restartPolicy", "Deployment".equalsIgnoreCase(kind) ? "Always" : "Never");

        if ("Deployment".equalsIgnoreCase(kind)) {
            Map<String, Object> matchLabels = new LinkedHashMap<>();
            matchLabels.put("app", name);
            Map<String, Object> template = new LinkedHashMap<>();
            template.put("metadata", new LinkedHashMap<>(Map.of("labels", matchLabels)));
            template.put("spec", podSpec);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("replicas", 1);
            spec.put("selector", new LinkedHashMap<>(Map.of("matchLabels", matchLabels)));
            spec.put("template", template);
            root.put("spec", spec);
        } else if ("CronJob".equalsIgnoreCase(kind)) {
            Map<String, Object> jobTemplate = new LinkedHashMap<>();
            jobTemplate.put("spec", new LinkedHashMap<>(Map.of("template", new LinkedHashMap<>(Map.of("spec", podSpec)))));
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("schedule", schedule == null || schedule.isBlank() ? "0 * * * *" : schedule);
            spec.put("jobTemplate", jobTemplate);
            root.put("spec", spec);
        } else {
            root.put("spec", new LinkedHashMap<>(Map.of("template", new LinkedHashMap<>(Map.of("spec", podSpec)))));
        }

        try {
            return YAML.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to render Kubernetes manifest", e);
        }
    }

    private boolean watchKubernetesResource(String kind, String name, String namespace, int watchSeconds, List<String> events) {
        long deadline = System.currentTimeMillis() + (watchSeconds * 1000L);
        String lowerKind = kind.toLowerCase(Locale.ROOT);
        while (System.currentTimeMillis() < deadline) {
            CommandResult result = kubectl(List.of("get", lowerKind, name, "-n", namespace, "-o", "yaml"));
            if (!result.isSuccess()) {
                events.add("watch-get-failed=" + safeError(result));
                return false;
            }
            String stdout = result.stdout();
            if ("job".equals(lowerKind)) {
                if (stdout.contains("type: Complete") || stdout.contains("succeeded: 1")) {
                    events.add("watch=job-complete");
                    return true;
                }
                if (stdout.contains("type: Failed") || stdout.contains("failed:")) {
                    events.add("watch=job-failed");
                    return false;
                }
            } else if ("deployment".equals(lowerKind)) {
                if (stdout.contains("availableReplicas: 1") || stdout.contains("type: Available")) {
                    events.add("watch=deployment-available");
                    return true;
                }
            } else if ("cronjob".equals(lowerKind)) {
                events.add("watch=cronjob-created");
                return true;
            }
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                events.add("watch=interrupted");
                return false;
            }
        }
        events.add("watch=timeout");
        return false;
    }

    private void deleteKubernetesResource(String kind, String name, String namespace, List<String> events) {
        CommandResult result = kubectl(List.of("delete", kind.toLowerCase(Locale.ROOT), name, "-n", namespace, "--ignore-not-found=true"));
        events.add("rollback-delete=" + safeError(result));
    }

    private void validateKubernetesSubmitRequest(KubernetesSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("submit request must not be null");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (request.templatePath() == null && (request.image() == null || request.image().isBlank())) {
            throw new IllegalArgumentException("image must not be blank when no template is provided");
        }
        if (request.allocation() != null && request.allocation().status() != AllocationStatus.ACTIVE) {
            throw new IllegalArgumentException("allocation must be ACTIVE for Kubernetes submit: " + request.allocation().id());
        }
        if (request.schedule() != null && !"CronJob".equalsIgnoreCase(request.kind())) {
            throw new IllegalArgumentException("schedule is only valid for kind=CronJob");
        }
        if (request.watchSeconds() < 0) {
            throw new IllegalArgumentException("watch-sec must be >= 0");
        }
        if (request.retryCount() < 0 || request.retryCount() > 10) {
            throw new IllegalArgumentException("retry must be between 0 and 10");
        }
        if (request.templatePath() != null) {
            requireRegularTemplateFile(request.templatePath());
        }
    }

    private Map<String, String> parseInlineEnv(List<String> envPairs) {
        Map<String, String> env = new LinkedHashMap<>();
        if (envPairs == null) {
            return env;
        }
        for (String pair : envPairs) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                throw new IllegalArgumentException("env must be KEY=value");
            }
            env.put(parts[0].trim(), parts[1]);
        }
        return env;
    }

    private String renderSlurmScript(
            AllocationRecord allocation,
            String entrypoint,
            List<String> extraArgs,
            int nnodes,
            int localGpus,
            Map<String, String> env
    ) {
        String command = "torchrun --nnodes " + nnodes
                + " --nproc-per-node " + localGpus
                + " --rdzv-backend c10d"
                + " --rdzv-endpoint " + env.getOrDefault("GPUM_RDZV_ENDPOINT", "localhost:29500")
                + " " + entrypoint
                + (extraArgs.isEmpty() ? "" : " " + String.join(" ", extraArgs));
        return "#!/usr/bin/env bash\n"
                + "#SBATCH --job-name=" + allocation.id() + "\n"
                + "#SBATCH --nodes=" + nnodes + "\n"
                + "#SBATCH --gpus-per-node=" + localGpus + "\n"
                + "export CUDA_VISIBLE_DEVICES=${CUDA_VISIBLE_DEVICES:-" + env.getOrDefault("CUDA_VISIBLE_DEVICES", "") + "}\n"
                + command + "\n";
    }

    private String renderRayCommand(
            AllocationRecord allocation,
            String entrypoint,
            List<String> extraArgs,
            Map<String, String> env
    ) {
        return "RAY_ADDRESS=auto "
                + env.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + singleQuote(entry.getValue()))
                .collect(Collectors.joining(" "))
                + " ray job submit --submission-id " + allocation.id()
                + " --working-dir . -- python "
                + singleQuote(entrypoint)
                + (extraArgs.isEmpty() ? "" : " " + extraArgs.stream().map(this::singleQuote).collect(Collectors.joining(" ")));
    }

    private List<String> mergeArgs(List<String> base, List<String> extra) {
        List<String> merged = new ArrayList<>(base);
        merged.addAll(extra);
        return merged;
    }

    private String safeError(CommandResult result) {
        if (result == null) {
            return "no-result";
        }
        if (result.stderr() != null && !result.stderr().isBlank()) {
            return result.stderr().trim();
        }
        if (result.stdout() != null && !result.stdout().isBlank()) {
            return result.stdout().trim();
        }
        return "exit=" + result.exitCode();
    }

    private String singleQuote(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "'\"'\"'") + "'";
    }

    private String escapeCmdArg(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(" ") || safe.contains("\"")) {
            return "\"" + safe.replace("\"", "\\\"") + "\"";
        }
        return safe;
    }

    private String escapeCmdValue(String value) {
        return value == null ? "" : value.replace("^", "^^").replace("&", "^&").replace("|", "^|").replace("<", "^<").replace(">", "^>");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Holds one AI tool launch plan and optionally the execution result. */
    public record AiLaunchPlan(
            String tool,
            Map<String, String> environment,
            List<String> baseCommand,
            List<String> wrappedCommand,
            CommandResult result
    ) {
    }

    /** Describes a preset launch recipe or rendered script for common AI training platforms. */
    public record AiPresetPlan(
            String tool,
            List<String> args,
            String renderedScript,
            List<String> notes
    ) {
    }

    /** Holds a Kubernetes submit preview or apply result after allocation-aware patching. */
    public record KubernetesSubmitPlan(
            String namespace,
            String manifestYaml,
            Path temporaryManifestPath,
            CommandResult result,
            List<String> events
    ) {
    }

    /** Describes one allocation-aware Kubernetes submit request with optional template and rollout controls. */
    public record KubernetesSubmitRequest(
            String name,
            String image,
            Integer gpus,
            String namespace,
            String kind,
            String schedule,
            Path templatePath,
            AllocationRecord allocation,
            List<String> envPairs,
            List<String> secretEnvPairs,
            String datasetPvc,
            String datasetMountPath,
            List<String> pvcMounts,
            int watchSeconds,
            int retryCount,
            boolean rollbackOnFail,
            boolean execute
    ) {
    }
}
