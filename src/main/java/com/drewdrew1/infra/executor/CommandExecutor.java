package com.drewdrew1.infra.executor;

import java.util.List;

/** Executes a command and returns captured stdout, stderr, and exit code. */
public interface CommandExecutor {
    CommandResult execute(List<String> command);
}
