package com.drewdrew1.infra.detector;

import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Provides deterministic command responses for detector and service tests. */
final class FakeCommandExecutor implements CommandExecutor {
    private final Map<String, CommandResult> results = new HashMap<>();

    FakeCommandExecutor addSuccess(List<String> command, String stdout) {
        results.put(String.join(" ", command), new CommandResult(command, 0, stdout, ""));
        return this;
    }

    FakeCommandExecutor addFailure(List<String> command, String stderr) {
        results.put(String.join(" ", command), new CommandResult(command, 1, "", stderr));
        return this;
    }

    @Override
    public CommandResult execute(List<String> command) {
        CommandResult result = results.get(String.join(" ", command));
        if (result == null) {
            throw new CommandExecutionException("Missing fake command result for: " + String.join(" ", command));
        }
        return result;
    }
}
