package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.service.IntegrationService;
import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes configured Kubernetes, MLflow, BentoML, AI launcher, and custom tool integrations. */
@Command(
        name = "integration",
        mixinStandardHelpOptions = true,
        description = "External platform integrations",
        subcommands = {
                IntegrationCommand.K8sCommand.class,
                IntegrationCommand.MlflowCommand.class,
                IntegrationCommand.BentomlCommand.class,
                IntegrationCommand.AiCommand.class,
                IntegrationCommand.ToolCommand.class
        }
)
public class IntegrationCommand implements Runnable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ParentCommand
    private GpuMgrCommand parent;

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(
            name = "k8s",
            description = "Kubernetes integration commands",
            subcommands = {
                    K8sCommand.ContextsCommand.class,
                    K8sCommand.PodsCommand.class,
                    K8sCommand.SubmitCommand.class,
                    K8sCommand.LogsCommand.class
            }
    )
    static class K8sCommand implements Runnable {
        @ParentCommand private IntegrationCommand integrationCommand;
        @Spec private CommandSpec spec;
        @Override public void run() { spec.commandLine().usage(System.out); }

        @Command(name = "contexts", description = "List Kubernetes contexts")
        static class ContextsCommand implements Callable<Integer> {
            @ParentCommand private K8sCommand k8sCommand;
            @Override public Integer call() {
                return execute(k8sCommand.integrationCommand, "integration.k8s", "contexts", List.of("config", "get-contexts", "-o", "name"));
            }
        }

        @Command(name = "pods", description = "List pods in the configured namespace")
        static class PodsCommand implements Callable<Integer> {
            @ParentCommand private K8sCommand k8sCommand;
            @Option(names = "--namespace") private String namespace;

            @Override public Integer call() {
                String ns = namespace;
                if (ns == null || ns.isBlank()) {
                    ns = k8sCommand.integrationCommand.parent.createContext().config().getKubernetes().getNamespace();
                }
                return execute(k8sCommand.integrationCommand, "integration.k8s", "pods", List.of("get", "pods", "-n", ns, "-o", "wide"));
            }
        }

        @Command(name = "submit", description = "Render or apply a patched Kubernetes manifest")
        static class SubmitCommand implements Callable<Integer> {
            @ParentCommand private K8sCommand k8sCommand;

            @Option(names = "--name", required = true) private String name;
            @Option(names = "--image") private String image;
            @Option(names = "--gpus") private Integer gpus;
            @Option(names = "--namespace") private String namespace;
            @Option(names = "--kind", defaultValue = "Job") private String kind;
            @Option(names = "--schedule") private String schedule;
            @Option(names = "--template") private Path template;
            @Option(names = "--allocation-id") private String allocationId;
            @Option(names = "--env", split = ",") private List<String> envPairs = List.of();
            @Option(names = "--secret-env", split = ",") private List<String> secretEnvPairs = List.of();
            @Option(names = "--dataset-pvc") private String datasetPvc;
            @Option(names = "--dataset-mount", defaultValue = "/datasets") private String datasetMount;
            @Option(names = "--mount-pvc", split = ",") private List<String> pvcMounts = List.of();
            @Option(names = "--watch-sec", defaultValue = "0") private int watchSeconds;
            @Option(names = "--retry", defaultValue = "0") private int retryCount;
            @Option(names = "--rollback-on-fail") private boolean rollbackOnFail;
            @Option(names = "--execute") private boolean execute;

            @Override public Integer call() {
                CliSupport.requireOneOf(kind.toLowerCase(), "kind", Set.of("job", "cronjob", "deployment"));
                if (gpus != null) {
                    CliSupport.requirePositive(gpus, "gpus");
                }
                AllocationRecord allocation = allocationId == null || allocationId.isBlank()
                        ? null
                        : requireAllocation(k8sCommand.integrationCommand, allocationId);

                IntegrationService.KubernetesSubmitPlan plan = k8sCommand.integrationCommand.parent.createContext()
                        .integrationService()
                        .kubernetesSubmitPlan(new IntegrationService.KubernetesSubmitRequest(
                                name,
                                image,
                                gpus,
                                namespace,
                                kind,
                                schedule,
                                template,
                                allocation,
                                envPairs,
                                secretEnvPairs,
                                datasetPvc,
                                datasetMount,
                                pvcMounts,
                                watchSeconds,
                                retryCount,
                                rollbackOnFail,
                                execute
                        ));

                logIntegration(k8sCommand.integrationCommand, "integration.k8s", "submit", execute ? "Applied Kubernetes manifest" : "Rendered Kubernetes manifest", name);
                System.out.print(plan.manifestYaml());
                if (!plan.events().isEmpty()) {
                    System.out.println();
                    System.out.println("Events:");
                    for (String event : plan.events()) {
                        System.out.println("- " + event);
                    }
                }
                if (plan.result() != null && !plan.result().stderr().isBlank()) {
                    System.err.print(plan.result().stderr());
                }
                return plan.result() == null || plan.result().isSuccess() ? 0 : 1;
            }
        }

        @Command(name = "logs", description = "Fetch pod logs through kubectl")
        static class LogsCommand implements Callable<Integer> {
            @ParentCommand private K8sCommand k8sCommand;
            @Parameters(index = "0", paramLabel = "POD") private String pod;
            @Option(names = "--namespace") private String namespace;

            @Override public Integer call() {
                CliSupport.requireNonBlank(pod, "pod");
                String ns = namespace;
                if (ns == null || ns.isBlank()) {
                    ns = k8sCommand.integrationCommand.parent.createContext().config().getKubernetes().getNamespace();
                }
                return execute(k8sCommand.integrationCommand, "integration.k8s", "logs", List.of("logs", pod, "-n", ns));
            }
        }
    }

    @Command(
            name = "ai",
            description = "AI training and inference tool integrations",
            subcommands = {
                    AiCommand.EnvCommand.class,
                    AiCommand.LaunchCommand.class,
                    AiCommand.PresetCommand.class
            }
    )
    static class AiCommand implements Runnable {
        @ParentCommand private IntegrationCommand integrationCommand;
        @Spec private CommandSpec spec;
        @Override public void run() { spec.commandLine().usage(System.out); }

        @Command(name = "env", description = "Render allocation-scoped environment for AI tooling")
        static class EnvCommand implements Callable<Integer> {
            @ParentCommand private AiCommand aiCommand;
            @Option(names = "--allocation-id", required = true) private String allocationId;
            @Option(names = "--format", defaultValue = "shell") private String format;

            @Override public Integer call() throws Exception {
                CliSupport.requireOneOf(format, "format", Set.of("shell", "cmd", "json"));
                AllocationRecord record = requireAllocation(aiCommand.integrationCommand, allocationId);
                Map<String, String> env = aiCommand.integrationCommand.parent.createContext().integrationService().allocationEnvironment(record);
                switch (format.toLowerCase()) {
                    case "json" -> System.out.println(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(env));
                    case "cmd" -> env.forEach((key, value) -> System.out.println("set " + key + "=" + value));
                    default -> env.forEach((key, value) -> System.out.println("export " + key + "=" + shellQuote(value)));
                }
                return 0;
            }
        }

        @Command(name = "launch", description = "Plan or execute an allocation-scoped AI tool launch")
        static class LaunchCommand implements Callable<Integer> {
            @ParentCommand private AiCommand aiCommand;
            @Option(names = "--allocation-id", required = true) private String allocationId;
            @Option(names = "--tool", required = true) private String tool;
            @Option(names = "--arg", arity = "0..*", description = "Tool arguments") private List<String> args = List.of();
            @Option(names = "--from-file", description = "Template file with one rendered argument per line") private Path fromFile;
            @Option(names = "--via-ssh") private boolean viaSsh;
            @Option(names = "--ssh-user") private String sshUser;
            @Option(names = "--execute") private boolean execute;

            @Override public Integer call() {
                AllocationRecord record = requireAllocation(aiCommand.integrationCommand, allocationId);
                List<String> resolvedArgs = resolveTemplateArgs(aiCommand.integrationCommand, record, tool, fromFile);
                resolvedArgs.addAll(args);
                IntegrationService.AiLaunchPlan plan = aiCommand.integrationCommand.parent.createContext()
                        .integrationService()
                        .aiLaunchPlan(record, tool, resolvedArgs, execute, viaSsh, sshUser);
                printLaunchPlan(plan);
                if (!execute) {
                    System.out.println("No process was started. Re-run with --execute to launch the tool.");
                    return 0;
                }
                if (plan.result() != null) {
                    if (!plan.result().stdout().isBlank()) {
                        System.out.print(plan.result().stdout());
                    }
                    if (!plan.result().stderr().isBlank()) {
                        System.err.print(plan.result().stderr());
                    }
                    return plan.result().isSuccess() ? 0 : 1;
                }
                return 0;
            }
        }

        @Command(
                name = "preset",
                description = "Generate preset launch specs for common AI runtimes",
                subcommands = {
                        PresetCommand.ListCommand.class,
                        PresetCommand.RenderCommand.class,
                        PresetCommand.LaunchCommand.class
                }
        )
        static class PresetCommand implements Runnable {
            @ParentCommand private AiCommand aiCommand;
            @Spec private CommandSpec spec;
            @Override public void run() { spec.commandLine().usage(System.out); }

            @Command(name = "list", description = "List supported AI presets")
            static class ListCommand implements Callable<Integer> {
                @Override public Integer call() {
                    System.out.println("torchrun-ddp");
                    System.out.println("accelerate");
                    System.out.println("deepspeed");
                    System.out.println("vllm-serve");
                    System.out.println("slurm-sbatch");
                    System.out.println("ray-job");
                    return 0;
                }
            }

            @Command(name = "render", description = "Render a preset command or script")
            static class RenderCommand implements Callable<Integer> {
                @ParentCommand private PresetCommand presetCommand;
                @Option(names = "--allocation-id", required = true) private String allocationId;
                @Option(names = "--name", required = true) private String name;
                @Option(names = "--entrypoint", required = true) private String entrypoint;
                @Option(names = "--arg", arity = "0..*") private List<String> args = List.of();

                @Override public Integer call() {
                    AllocationRecord record = requireAllocation(presetCommand.aiCommand.integrationCommand, allocationId);
                    IntegrationService.AiPresetPlan plan = presetCommand.aiCommand.integrationCommand.parent.createContext()
                            .integrationService()
                            .aiPresetPlan(name, record, entrypoint, args);
                    printPresetPlan(plan);
                    return 0;
                }
            }

            @Command(name = "launch", description = "Launch one executable preset or print script-based presets")
            static class LaunchCommand implements Callable<Integer> {
                @ParentCommand private PresetCommand presetCommand;
                @Option(names = "--allocation-id", required = true) private String allocationId;
                @Option(names = "--name", required = true) private String name;
                @Option(names = "--entrypoint", required = true) private String entrypoint;
                @Option(names = "--arg", arity = "0..*") private List<String> args = List.of();
                @Option(names = "--via-ssh") private boolean viaSsh;
                @Option(names = "--ssh-user") private String sshUser;
                @Option(names = "--execute") private boolean execute;

                @Override public Integer call() {
                    AllocationRecord record = requireAllocation(presetCommand.aiCommand.integrationCommand, allocationId);
                    IntegrationService.AiPresetPlan preset = presetCommand.aiCommand.integrationCommand.parent.createContext()
                            .integrationService()
                            .aiPresetPlan(name, record, entrypoint, args);
                    if (preset.renderedScript() != null) {
                        printPresetPlan(preset);
                        System.out.println("This preset renders a launch spec/script. Execution is intentionally blocked here.");
                        return 0;
                    }
                    IntegrationService.AiLaunchPlan plan = presetCommand.aiCommand.integrationCommand.parent.createContext()
                            .integrationService()
                            .aiLaunchPlan(record, preset.tool(), preset.args(), execute, viaSsh, sshUser);
                    printLaunchPlan(plan);
                    if (!execute) {
                        System.out.println("No process was started. Re-run with --execute to launch the tool.");
                        return 0;
                    }
                    if (plan.result() != null) {
                        if (!plan.result().stdout().isBlank()) {
                            System.out.print(plan.result().stdout());
                        }
                        if (!plan.result().stderr().isBlank()) {
                            System.err.print(plan.result().stderr());
                        }
                        return plan.result().isSuccess() ? 0 : 1;
                    }
                    return 0;
                }
            }
        }

        private static List<String> resolveTemplateArgs(IntegrationCommand integrationCommand, AllocationRecord record, String tool, Path fromFile) {
            List<String> resolvedArgs = new ArrayList<>();
            if (fromFile == null) {
                return resolvedArgs;
            }
            Map<String, String> vars = new LinkedHashMap<>(integrationCommand.parent.createContext().integrationService().allocationEnvironment(record));
            vars.put("GPUM_TOOL", tool);
            vars.put("GPUM_PRIMARY_NODE", record.primaryNodeHostname() == null ? "" : record.primaryNodeHostname());
            vars.put("GPUM_GPU_COUNT", Integer.toString(record.devices().size()));
            resolvedArgs.addAll(integrationCommand.parent.createContext().integrationService().loadArgumentTemplate(fromFile, vars));
            return resolvedArgs;
        }

        private static void printLaunchPlan(IntegrationService.AiLaunchPlan plan) {
            System.out.println("Tool: " + plan.tool());
            System.out.println("Base command: " + String.join(" ", plan.baseCommand()));
            System.out.println("Wrapped command: " + String.join(" ", plan.wrappedCommand()));
            System.out.println("Environment:");
            plan.environment().forEach((key, value) -> System.out.println("- " + key + "=" + value));
        }

        private static void printPresetPlan(IntegrationService.AiPresetPlan plan) {
            System.out.println("Preset tool: " + plan.tool());
            if (plan.renderedScript() != null) {
                System.out.println(plan.renderedScript());
            } else {
                System.out.println("Arguments: " + String.join(" ", plan.args()));
            }
            if (!plan.notes().isEmpty()) {
                System.out.println("Notes:");
                for (String note : plan.notes()) {
                    System.out.println("- " + note);
                }
            }
        }
    }

    @Command(
            name = "mlflow",
            description = "MLflow integration commands",
            subcommands = {
                    MlflowCommand.StatusCommand.class,
                    MlflowCommand.RunsCommand.class,
                    MlflowCommand.ModelsCommand.class
            }
    )
    static class MlflowCommand implements Runnable {
        @ParentCommand private IntegrationCommand integrationCommand;
        @Spec private CommandSpec spec;
        @Override public void run() { spec.commandLine().usage(System.out); }

        @Command(name = "status", description = "Show the effective MLflow configuration")
        static class StatusCommand implements Callable<Integer> {
            @ParentCommand private MlflowCommand mlflowCommand;
            @Override public Integer call() {
                var config = mlflowCommand.integrationCommand.parent.createContext().config().getMlflow();
                System.out.printf("enabled=%s%n", config.isEnabled());
                System.out.printf("trackingUri=%s%n", CliSupport.safe(config.getTrackingUri()));
                System.out.printf("registryUri=%s%n", CliSupport.safe(config.getRegistryUri()));
                System.out.printf("experiment=%s%n", CliSupport.safe(config.getExperiment()));
                System.out.printf("profile=%s%n", CliSupport.safe(config.getProfile()));
                return 0;
            }
        }

        @Command(name = "runs", description = "List runs through the MLflow CLI")
        static class RunsCommand implements Callable<Integer> {
            @ParentCommand private MlflowCommand mlflowCommand;
            @Option(names = "--experiment") private String experiment;
            @Option(names = "--limit", defaultValue = "20") private int limit;
            @Override public Integer call() {
                CliSupport.requirePositive(limit, "limit");
                List<String> command = new ArrayList<>(List.of("runs", "list", "--max-results", Integer.toString(limit)));
                if (experiment != null && !experiment.isBlank()) {
                    command.add("--experiment-name");
                    command.add(experiment);
                }
                return execute(mlflowCommand.integrationCommand, "integration.mlflow", "runs", command);
            }
        }

        @Command(name = "models", description = "List registered models through the MLflow CLI")
        static class ModelsCommand implements Callable<Integer> {
            @ParentCommand private MlflowCommand mlflowCommand;
            @Option(names = "--limit", defaultValue = "20") private int limit;
            @Override public Integer call() {
                CliSupport.requirePositive(limit, "limit");
                return execute(mlflowCommand.integrationCommand, "integration.mlflow", "models", List.of("models", "list", "--max-results", Integer.toString(limit)));
            }
        }
    }

    @Command(
            name = "bentoml",
            description = "BentoML integration commands",
            subcommands = {
                    BentomlCommand.ListCommand.class,
                    BentomlCommand.ModelsCommand.class,
                    BentomlCommand.ServeCommand.class
            }
    )
    static class BentomlCommand implements Runnable {
        @ParentCommand private IntegrationCommand integrationCommand;
        @Spec private CommandSpec spec;
        @Override public void run() { spec.commandLine().usage(System.out); }

        @Command(name = "list", description = "List Bentos")
        static class ListCommand implements Callable<Integer> {
            @ParentCommand private BentomlCommand bentomlCommand;
            @Override public Integer call() {
                return execute(bentomlCommand.integrationCommand, "integration.bentoml", "list", List.of("list"));
            }
        }

        @Command(name = "models", description = "List BentoML models")
        static class ModelsCommand implements Callable<Integer> {
            @ParentCommand private BentomlCommand bentomlCommand;
            @Override public Integer call() {
                return execute(bentomlCommand.integrationCommand, "integration.bentoml", "models", List.of("models", "list"));
            }
        }

        @Command(name = "serve", description = "Start a Bento service")
        static class ServeCommand implements Callable<Integer> {
            @ParentCommand private BentomlCommand bentomlCommand;
            @Option(names = "--bento", required = true) private String bento;
            @Option(names = "--port", defaultValue = "3000") private int port;
            @Override public Integer call() {
                CliSupport.requireRange(port, 1, 65535, "port");
                return execute(bentomlCommand.integrationCommand, "integration.bentoml", "serve", List.of("serve", bento, "--port", Integer.toString(port)));
            }
        }
    }

    @Command(name = "tool", description = "Run a custom configured external tool")
    static class ToolCommand implements Callable<Integer> {
        @ParentCommand private IntegrationCommand integrationCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--arg", arity = "0..*", description = "Additional arguments") private List<String> args = List.of();

        @Override public Integer call() {
            CliSupport.requireNonBlank(name, "name");
            try {
                CommandResult result = integrationCommand.parent.createContext().integrationService().externalTool(name, args);
                integrationCommand.parent.createContext().logService().info("integration", "tool", "Ran custom tool " + name, String.join(" ", args));
                System.out.print(result.stdout());
                if (!result.stderr().isBlank()) {
                    System.err.print(result.stderr());
                }
                return result.isSuccess() ? 0 : 1;
            } catch (CommandExecutionException e) {
                integrationCommand.parent.createContext().logService().error("integration", "tool", "Custom tool failed: " + name, e.getMessage());
                throw e;
            }
        }
    }

    private static Integer execute(IntegrationCommand integrationCommand, String component, String category, List<String> args) {
        try {
            CommandResult result;
            if (component.endsWith("k8s")) {
                result = integrationCommand.parent.createContext().integrationService().kubectl(args);
            } else if (component.endsWith("mlflow")) {
                result = integrationCommand.parent.createContext().integrationService().mlflow(args);
            } else {
                result = integrationCommand.parent.createContext().integrationService().bentoml(args);
            }
            logIntegration(integrationCommand, component, category, "Executed external command", String.join(" ", args));
            if (!result.stdout().isBlank()) {
                System.out.print(result.stdout());
            }
            if (!result.stderr().isBlank()) {
                System.err.print(result.stderr());
            }
            return result.isSuccess() ? 0 : 1;
        } catch (CommandExecutionException e) {
            integrationCommand.parent.createContext().logService().error(component, category, "External command failed", e.getMessage());
            throw e;
        }
    }

    private static void logIntegration(IntegrationCommand integrationCommand, String component, String category, String message, String detail) {
        integrationCommand.parent.createContext().logService().info(component, category, message, detail);
        integrationCommand.parent.createContext().auditService().log("INTEGRATION_EXEC", actor(), component, detail);
    }

    private static String actor() {
        return System.getProperty("user.name", "unknown");
    }

    private static AllocationRecord requireAllocation(IntegrationCommand integrationCommand, String allocationId) {
        CliSupport.requireNonBlank(allocationId, "allocation-id");
        return integrationCommand.parent.createContext().allocationService().findAllocation(allocationId)
                .orElseThrow(() -> new IllegalArgumentException("Allocation not found: " + allocationId));
    }

    private static String shellQuote(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "'\"'\"'") + "'";
    }
}
