package com.drewdrew1.infra.executor;

/** Signals a command execution failure before a valid result is produced. */
public class CommandExecutionException extends RuntimeException {
    public CommandExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public CommandExecutionException(String message) {
        super(message);
    }
}
