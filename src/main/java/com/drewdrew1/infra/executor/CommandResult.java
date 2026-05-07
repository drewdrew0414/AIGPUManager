package com.drewdrew1.infra.executor;

import java.util.List;

public record CommandResult(
        List<String> command,
        int exitCode,
        String stdout,
        String stderr
) {
    public boolean isSuccess() {
        return exitCode == 0;
    }
}
