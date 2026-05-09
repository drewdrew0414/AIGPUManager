package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.OpsRecord;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes secret reference management without storing raw secret values by default. */
@Command(
        name = "secret",
        mixinStandardHelpOptions = true,
        description = "Secret references for safe environment injection",
        subcommands = {
                SecretCommand.PutCommand.class,
                SecretCommand.ListCommand.class,
                SecretCommand.RenderCommand.class
        }
)
public class SecretCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "put", description = "Store a Vault/Kubernetes/env secret reference")
    static class PutCommand implements Callable<Integer> {
        @ParentCommand private SecretCommand secretCommand;
        @Option(names = "--name", required = true) private String name;
        @Option(names = "--provider", required = true) private String provider;
        @Option(names = "--ref", required = true) private String ref;
        @Option(names = "--env", required = true) private String envName;

        @Override public Integer call() {
            CliSupport.requireOneOf(provider, "provider", Set.of("vault", "k8s", "env", "aws", "gcp", "azure"));
            OpsRecord record = secretCommand.parent.createContext().enterpriseOpsService().secretRef(name, provider, ref, envName);
            System.out.printf("Stored secret reference %s id=%s%n", record.name(), record.id());
            return 0;
        }
    }

    @Command(name = "list", description = "List secret references")
    static class ListCommand implements Callable<Integer> {
        @ParentCommand private SecretCommand secretCommand;
        @Override public Integer call() {
            ComputeCommand.printRecords(secretCommand.parent.createContext().enterpriseOpsService().list("secret", "ref"));
            return 0;
        }
    }

    @Command(name = "render", description = "Render one secret reference as shell/cmd/json environment")
    static class RenderCommand implements Callable<Integer> {
        @ParentCommand private SecretCommand secretCommand;
        @Option(names = "--id", required = true) private String id;
        @Option(names = "--format", defaultValue = "shell") private String format;

        @Override public Integer call() {
            CliSupport.requireOneOf(format, "format", Set.of("shell", "cmd", "json"));
            OpsRecord record = secretCommand.parent.createContext().enterpriseOpsService().find(id)
                    .orElseThrow(() -> new IllegalArgumentException("Secret reference not found: " + id));
            System.out.println(secretCommand.parent.createContext().enterpriseOpsService().renderSecretEnv(record, format));
            return 0;
        }
    }
}
