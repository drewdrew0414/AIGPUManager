package com.drewdrew1.infra.executor;

import java.util.List;

public interface CommandExecutor {
    CommandResult execute(List<String> command);
}
