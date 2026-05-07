package com.drewdrew1.infra.executor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Runs commands on a remote host by delegating through the local ssh client. */
public class SshCommandExecutor implements CommandExecutor {
    private final LocalCommandExecutor localExecutor;
    private final String address;
    private final String sshUser;
    private final String sshCommand;

    public SshCommandExecutor(Duration timeout, String address, String sshUser, String sshCommand) {
        this.localExecutor = new LocalCommandExecutor(timeout);
        this.address = address;
        this.sshUser = sshUser;
        this.sshCommand = sshCommand;
    }

    @Override
    public CommandResult execute(List<String> command) {
        String remoteCommand = quoteCommand(command);
        List<String> ssh = new ArrayList<>();
        ssh.add(sshCommand);
        ssh.add("-o");
        ssh.add("BatchMode=yes");
        ssh.add("-o");
        ssh.add("ConnectTimeout=10");
        ssh.add(sshUser + "@" + address);
        ssh.add(remoteCommand);
        return localExecutor.execute(ssh);
    }

    private String quoteCommand(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append('\'').append(command.get(i).replace("'", "'\"'\"'")).append('\'');
        }
        return sb.toString();
    }
}
