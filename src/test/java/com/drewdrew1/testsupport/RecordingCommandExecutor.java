package com.drewdrew1.testsupport;

import com.drewdrew1.infra.executor.CommandExecutionException;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.CommandResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Records executed commands and returns deterministic fake command results. */
public final class RecordingCommandExecutor implements CommandExecutor {
    private final Map<String, CommandResult> results = new HashMap<>();
    private final List<List<String>> executed = new ArrayList<>();

    public RecordingCommandExecutor addSuccess(List<String> command, String stdout) {
        results.put(String.join(" ", command), new CommandResult(command, 0, stdout, ""));
        return this;
    }

    public RecordingCommandExecutor addFailure(List<String> command, String stderr) {
        results.put(String.join(" ", command), new CommandResult(command, 1, "", stderr));
        return this;
    }

    public List<List<String>> executed() {
        return List.copyOf(executed);
    }

    @Override
    public CommandResult execute(List<String> command) {
        executed.add(List.copyOf(command));
        CommandResult result = results.get(String.join(" ", command));
        if (result == null) {
            throw new CommandExecutionException("Missing fake command result for: " + String.join(" ", command));
        }
        return result;
    }
}
