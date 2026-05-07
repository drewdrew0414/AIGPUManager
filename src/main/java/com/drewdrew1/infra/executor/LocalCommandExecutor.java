package com.drewdrew1.infra.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class LocalCommandExecutor implements CommandExecutor {
    private final Duration timeout;

    public LocalCommandExecutor(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public CommandResult execute(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new CommandExecutionException("Timed out executing command: " + String.join(" ", command));
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(command, process.exitValue(), stdout, stderr);
        } catch (IOException e) {
            throw new CommandExecutionException("Failed to execute command: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommandExecutionException("Interrupted while executing command: " + String.join(" ", command), e);
        }
    }
}
