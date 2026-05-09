package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;

/** Exposes developer-experience helpers for packaging, completion, and SDK scaffolding. */
@Command(
        name = "dev",
        mixinStandardHelpOptions = true,
        description = "Completion, native-image plan, and Python SDK scaffold",
        subcommands = {
                DevCommand.CompletionCommand.class,
                DevCommand.NativeCommand.class,
                DevCommand.PythonSdkCommand.class,
                DevCommand.TerminalCommand.class
        }
)
public class DevCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;
    @Override public void run() { spec.commandLine().usage(System.out); }

    @Command(name = "completion", description = "Print shell completion install hint")
    static class CompletionCommand implements Callable<Integer> {
        @Option(names = "--shell", defaultValue = "bash") private String shell;
        @Override public Integer call() {
            CliSupport.requireOneOf(shell, "shell", Set.of("bash", "zsh", "powershell"));
            if ("powershell".equalsIgnoreCase(shell)) {
                System.out.println("gpum completion generation is planned; use picocli AutoComplete during packaging for PowerShell profile install.");
            } else {
                System.out.println("gpum completion generation is planned; package with picocli AutoComplete and source the generated " + shell + " script.");
            }
            return 0;
        }
    }

    @Command(name = "native", description = "Print GraalVM native-image build plan")
    static class NativeCommand implements Callable<Integer> {
        @Override public Integer call() {
            System.out.println("native-image --no-fallback -H:Name=gpum -cp build/libs/gpu-mgr.jar com.drewdrew1.App");
            System.out.println("Note: SQLite JDBC, JNA, and reflection-heavy CLI paths require native-image metadata before production use.");
            return 0;
        }
    }

    @Command(name = "python-sdk", description = "Write a small Python wrapper SDK")
    static class PythonSdkCommand implements Callable<Integer> {
        @ParentCommand private DevCommand devCommand;
        @Option(names = "--output", defaultValue = "build/python-sdk/gpum_client.py") private Path output;
        @Override public Integer call() {
            Path written = devCommand.parent.createContext().enterpriseOpsService().writePythonSdk(output);
            System.out.println("Wrote Python SDK scaffold: " + written);
            return 0;
        }
    }

    @Command(name = "terminal", description = "Verify JLine3 terminal integration")
    static class TerminalCommand implements Callable<Integer> {
        @Override public Integer call() throws Exception {
            try (Terminal terminal = TerminalBuilder.builder().dumb(true).build()) {
                System.out.printf("JLine terminal type=%s size=%dx%d%n",
                        terminal.getType(),
                        terminal.getWidth(),
                        terminal.getHeight());
            }
            return 0;
        }
    }
}
