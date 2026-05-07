package com.drewdrew1.infra.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Runs commands on the local machine with a bounded timeout. */
public class LocalCommandExecutor implements CommandExecutor {
    private final Duration timeout;

    public LocalCommandExecutor(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public CommandResult execute(List<String> command) {
        CommandExecutionException firstError = null;
        List<String> attempted = new ArrayList<>();
        for (List<String> candidate : commandCandidates(command)) {
            attempted.add(candidate.getFirst());
            try {
                Process process = new ProcessBuilder(candidate).start();
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new CommandExecutionException("Timed out executing command: " + String.join(" ", candidate));
                }

                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                return new CommandResult(candidate, process.exitValue(), stdout, stderr);
            } catch (IOException e) {
                if (firstError == null) {
                    firstError = new CommandExecutionException("Failed to execute command: " + String.join(" ", command), e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CommandExecutionException("Interrupted while executing command: " + String.join(" ", candidate), e);
            }
        }
        String detail = attempted.isEmpty() ? "" : " (tried: " + String.join(", ", attempted) + ")";
        throw firstError == null
                ? new CommandExecutionException("Failed to execute command: " + String.join(" ", command) + detail)
                : new CommandExecutionException("Failed to execute command: " + String.join(" ", command) + detail, firstError);
    }

    private List<List<String>> commandCandidates(List<String> command) {
        List<List<String>> candidates = new ArrayList<>();
        candidates.add(command);
        if (!isWindows() || command.isEmpty()) {
            return candidates;
        }
        String executable = command.getFirst();
        if (executable.contains(".") || executable.contains("\\") || executable.contains("/")) {
            return candidates;
        }
        for (String ext : List.of(".exe", ".cmd", ".bat", ".com")) {
            List<String> candidate = new ArrayList<>(command);
            candidate.set(0, executable + ext);
            candidates.add(candidate);
        }
        return candidates;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
