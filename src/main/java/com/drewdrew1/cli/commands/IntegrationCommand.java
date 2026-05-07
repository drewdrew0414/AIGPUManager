package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Exposes configured Kubernetes, MLflow, BentoML, and custom tool integrations. */
@Command(
        name = "integration",
        mixinStandardHelpOptions = true,
        description = "External platform integrations",
        subcommands = {
                IntegrationCommand.K8sCommand.class,
                IntegrationCommand.MlflowCommand.class,
                IntegrationCommand.BentomlCommand.class,
                IntegrationCommand.ToolCommand.class
        }
)
public class IntegrationCommand implements Runnable {
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
        @ParentCommand
        private IntegrationCommand integrationCommand;

        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "contexts", description = "List Kubernetes contexts")
        static class ContextsCommand implements Callable<Integer> {
            @ParentCommand
            private K8sCommand k8sCommand;

            @Override
            public Integer call() {
                return execute(
                        k8sCommand.integrationCommand,
                        "integration.k8s",
                        "contexts",
                        List.of("config", "get-contexts", "-o", "name")
                );
            }
        }

        @Command(name = "pods", description = "List pods in the configured namespace")
        static class PodsCommand implements Callable<Integer> {
            @ParentCommand
            private K8sCommand k8sCommand;

            @Option(names = "--namespace")
            private String namespace;

            @Override
            public Integer call() {
                String ns = namespace;
                if (ns == null || ns.isBlank()) {
                    ns = k8sCommand.integrationCommand.parent.createContext().config().getKubernetes().getNamespace();
                }
                return execute(
                        k8sCommand.integrationCommand,
                        "integration.k8s",
                        "pods",
                        List.of("get", "pods", "-n", ns, "-o", "wide")
                );
            }
        }

        @Command(name = "submit", description = "Submit a simple GPU job manifest through kubectl")
        static class SubmitCommand implements Callable<Integer> {
            @ParentCommand
            private K8sCommand k8sCommand;

            @Option(names = "--name", required = true)
            private String name;

            @Option(names = "--image", required = true)
            private String image;

            @Option(names = "--gpus", defaultValue = "1")
            private int gpus;

            @Option(names = "--namespace")
            private String namespace;

            @Option(names = "--allocation-id")
            private String allocationId;

            @Override
            public Integer call() {
                CliSupport.requirePositive(gpus, "gpus");
                String ns = namespace;
                if (ns == null || ns.isBlank()) {
                    ns = k8sCommand.integrationCommand.parent.createContext().config().getKubernetes().getNamespace();
                }
                List<String> command = new ArrayList<>(List.of(
                        "create",
                        "job",
                        name,
                        "--image=" + image,
                        "-n",
                        ns,
                        "--dry-run=client",
                        "-o",
                        "yaml"
                ));
                if (allocationId != null && !allocationId.isBlank()) {
                    command.add("--labels=gpum_allocation=" + allocationId);
                }
                return execute(k8sCommand.integrationCommand, "integration.k8s", "submit", command);
            }
        }

        @Command(name = "logs", description = "Fetch pod logs through kubectl")
        static class LogsCommand implements Callable<Integer> {
            @ParentCommand
            private K8sCommand k8sCommand;

            @Parameters(index = "0", paramLabel = "POD")
            private String pod;

            @Option(names = "--namespace")
            private String namespace;

            @Override
            public Integer call() {
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
            name = "mlflow",
            description = "MLflow integration commands",
            subcommands = {
                    MlflowCommand.StatusCommand.class,
                    MlflowCommand.RunsCommand.class,
                    MlflowCommand.ModelsCommand.class
            }
    )
    static class MlflowCommand implements Runnable {
        @ParentCommand
        private IntegrationCommand integrationCommand;

        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "status", description = "Show the effective MLflow configuration")
        static class StatusCommand implements Callable<Integer> {
            @ParentCommand
            private MlflowCommand mlflowCommand;

            @Override
            public Integer call() {
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
            @ParentCommand
            private MlflowCommand mlflowCommand;

            @Option(names = "--experiment")
            private String experiment;

            @Option(names = "--limit", defaultValue = "20")
            private int limit;

            @Override
            public Integer call() {
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
            @ParentCommand
            private MlflowCommand mlflowCommand;

            @Option(names = "--limit", defaultValue = "20")
            private int limit;

            @Override
            public Integer call() {
                CliSupport.requirePositive(limit, "limit");
                return execute(
                        mlflowCommand.integrationCommand,
                        "integration.mlflow",
                        "models",
                        List.of("models", "list", "--max-results", Integer.toString(limit))
                );
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
        @ParentCommand
        private IntegrationCommand integrationCommand;

        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "list", description = "List Bentos")
        static class ListCommand implements Callable<Integer> {
            @ParentCommand
            private BentomlCommand bentomlCommand;

            @Override
            public Integer call() {
                return execute(bentomlCommand.integrationCommand, "integration.bentoml", "list", List.of("list"));
            }
        }

        @Command(name = "models", description = "List BentoML models")
        static class ModelsCommand implements Callable<Integer> {
            @ParentCommand
            private BentomlCommand bentomlCommand;

            @Override
            public Integer call() {
                return execute(bentomlCommand.integrationCommand, "integration.bentoml", "models", List.of("models", "list"));
            }
        }

        @Command(name = "serve", description = "Start a Bento service")
        static class ServeCommand implements Callable<Integer> {
            @ParentCommand
            private BentomlCommand bentomlCommand;

            @Option(names = "--bento", required = true)
            private String bento;

            @Option(names = "--port", defaultValue = "3000")
            private int port;

            @Override
            public Integer call() {
                CliSupport.requireRange(port, 1, 65535, "port");
                return execute(
                        bentomlCommand.integrationCommand,
                        "integration.bentoml",
                        "serve",
                        List.of("serve", bento, "--port", Integer.toString(port))
                );
            }
        }
    }

    @Command(name = "tool", description = "Run a custom configured external tool")
    static class ToolCommand implements Callable<Integer> {
        @ParentCommand
        private IntegrationCommand integrationCommand;

        @Option(names = "--name", required = true)
        private String name;

        @Option(names = "--arg", arity = "0..*", description = "Additional arguments")
        private List<String> args = List.of();

        @Override
        public Integer call() {
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

    private static Integer execute(
            IntegrationCommand integrationCommand,
            String component,
            String category,
            List<String> args
    ) {
        try {
            CommandResult result;
            if (component.endsWith("k8s")) {
                result = integrationCommand.parent.createContext().integrationService().kubectl(args);
            } else if (component.endsWith("mlflow")) {
                result = integrationCommand.parent.createContext().integrationService().mlflow(args);
            } else {
                result = integrationCommand.parent.createContext().integrationService().bentoml(args);
            }
            integrationCommand.parent.createContext().logService().info(component, category, "Executed external command", String.join(" ", args));
            integrationCommand.parent.createContext().auditService().log("INTEGRATION_EXEC", actor(), component, String.join(" ", args));
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

    private static String actor() {
        return System.getProperty("user.name", "unknown");
    }
}
