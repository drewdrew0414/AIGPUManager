package com.drewdrew1.cli.commands;

import com.drewdrew1.cli.CliSupport;
import com.drewdrew1.cli.GpuMgrCommand;
import com.drewdrew1.core.model.ApprovalRequest;
import com.drewdrew1.core.model.ApprovalStatus;
import com.drewdrew1.core.model.RbacRole;
import com.drewdrew1.core.model.RoleBinding;
import com.github.freva.asciitable.AsciiTable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/** Exposes tenant-scoped RBAC bindings and approval workflows. */
@Command(
        name = "rbac",
        mixinStandardHelpOptions = true,
        description = "Role bindings and approval workflow",
        subcommands = {
                RbacCommand.RoleCommand.class,
                RbacCommand.ApprovalCommand.class,
                RbacCommand.WhoAmICommand.class
        }
)
public class RbacCommand implements Runnable {
    @ParentCommand private GpuMgrCommand parent;
    @Spec private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(
            name = "role",
            description = "Manage RBAC role bindings",
            subcommands = {
                    RoleCommand.GrantCommand.class,
                    RoleCommand.RevokeCommand.class,
                    RoleCommand.ListCommand.class
            }
    )
    static class RoleCommand implements Runnable {
        @ParentCommand private RbacCommand rbacCommand;
        @Spec private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "grant", description = "Grant one role to one actor")
        static class GrantCommand implements Callable<Integer> {
            @ParentCommand private RoleCommand roleCommand;
            @Option(names = "--actor", required = true) private String actor;
            @Option(names = "--role", required = true) private String role;
            @Option(names = "--tenant") private String tenant;

            @Override
            public Integer call() {
                CliSupport.requireNonBlank(actor, "actor");
                roleCommand.rbacCommand.parent.createContext().accessControlService()
                        .grantRole(CliSupport.currentActor(), actor, parseRole(role), tenant);
                System.out.printf("Granted %s to %s%s%n",
                        role.toUpperCase(Locale.ROOT),
                        actor,
                        tenant == null ? "" : " on tenant " + tenant);
                return 0;
            }
        }

        @Command(name = "revoke", description = "Revoke one role binding")
        static class RevokeCommand implements Callable<Integer> {
            @ParentCommand private RoleCommand roleCommand;
            @Option(names = "--actor", required = true) private String actor;
            @Option(names = "--role", required = true) private String role;
            @Option(names = "--tenant") private String tenant;

            @Override
            public Integer call() {
                CliSupport.requireNonBlank(actor, "actor");
                int removed = roleCommand.rbacCommand.parent.createContext().accessControlService()
                        .revokeRole(CliSupport.currentActor(), actor, parseRole(role), tenant);
                System.out.printf("Removed %d role binding(s).%n", removed);
                return 0;
            }
        }

        @Command(name = "list", description = "List role bindings")
        static class ListCommand implements Callable<Integer> {
            @ParentCommand private RoleCommand roleCommand;
            @Option(names = "--actor") private String actor;
            @Option(names = "--tenant") private String tenant;

            @Override
            public Integer call() {
                List<RoleBinding> bindings = new ArrayList<>(roleCommand.rbacCommand.parent.createContext()
                        .accessControlService().listRoleBindings());
                if (actor != null) {
                    bindings.removeIf(binding -> !binding.actor().equalsIgnoreCase(actor));
                }
                if (tenant != null) {
                    bindings.removeIf(binding -> !"TENANT".equalsIgnoreCase(binding.scopeType())
                            || binding.scopeName() == null
                            || !binding.scopeName().equalsIgnoreCase(tenant));
                }
                if (bindings.isEmpty()) {
                    System.out.println("No role bindings found.");
                    return 0;
                }
                List<String[]> rows = new ArrayList<>();
                for (RoleBinding binding : bindings) {
                    rows.add(new String[]{
                            binding.actor(),
                            binding.role().name(),
                            binding.scopeType(),
                            binding.scopeName() == null ? "-" : binding.scopeName(),
                            binding.createdBy(),
                            binding.createdAt().toString()
                    });
                }
                System.out.println(AsciiTable.getTable(
                        new String[]{"Actor", "Role", "ScopeType", "ScopeName", "CreatedBy", "CreatedAt"},
                        rows.toArray(String[][]::new)
                ));
                return 0;
            }
        }
    }

    @Command(
            name = "approval",
            description = "Review approval requests",
            subcommands = {
                    ApprovalCommand.ListCommand.class,
                    ApprovalCommand.ApproveCommand.class,
                    ApprovalCommand.DenyCommand.class
            }
    )
    static class ApprovalCommand implements Runnable {
        @ParentCommand private RbacCommand rbacCommand;
        @Spec private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }

        @Command(name = "list", description = "List approval requests")
        static class ListCommand implements Callable<Integer> {
            @ParentCommand private ApprovalCommand approvalCommand;
            @Option(names = "--status") private String status;
            @Option(names = "--mine") private boolean mine;

            @Override
            public Integer call() {
                List<ApprovalRequest> requests = new ArrayList<>(approvalCommand.rbacCommand.parent.createContext()
                        .accessControlService().listApprovalRequests());
                if (status != null) {
                    ApprovalStatus parsed = ApprovalStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
                    requests.removeIf(request -> request.status() != parsed);
                }
                if (mine) {
                    String actor = CliSupport.currentActor();
                    requests.removeIf(request -> !request.requester().equalsIgnoreCase(actor)
                            && (request.approvedBy() == null || !request.approvedBy().equalsIgnoreCase(actor)));
                }
                if (requests.isEmpty()) {
                    System.out.println("No approval requests found.");
                    return 0;
                }
                List<String[]> rows = new ArrayList<>();
                for (ApprovalRequest request : requests) {
                    rows.add(new String[]{
                            request.id(),
                            request.status().name(),
                            request.requester(),
                            request.requiredRole().name(),
                            request.action(),
                            request.resourceType(),
                            request.resourceId(),
                            request.tenant() == null ? "-" : request.tenant(),
                            request.approvedBy() == null ? "-" : request.approvedBy()
                    });
                }
                System.out.println(AsciiTable.getTable(
                        new String[]{"Id", "Status", "Requester", "Role", "Action", "ResourceType", "ResourceId", "Tenant", "ApprovedBy"},
                        rows.toArray(String[][]::new)
                ));
                return 0;
            }
        }

        @Command(name = "approve", description = "Approve one request")
        static class ApproveCommand implements Callable<Integer> {
            @ParentCommand private ApprovalCommand approvalCommand;
            @Option(names = "--id", required = true) private String id;
            @Option(names = "--reason") private String reason;

            @Override
            public Integer call() {
                ApprovalRequest request = approvalCommand.rbacCommand.parent.createContext().accessControlService()
                        .approve(CliSupport.currentActor(), id, reason);
                System.out.printf("Approved %s for %s%n", request.id(), request.requester());
                return 0;
            }
        }

        @Command(name = "deny", description = "Deny one request")
        static class DenyCommand implements Callable<Integer> {
            @ParentCommand private ApprovalCommand approvalCommand;
            @Option(names = "--id", required = true) private String id;
            @Option(names = "--reason") private String reason;

            @Override
            public Integer call() {
                ApprovalRequest request = approvalCommand.rbacCommand.parent.createContext().accessControlService()
                        .deny(CliSupport.currentActor(), id, reason);
                System.out.printf("Denied %s for %s%n", request.id(), request.requester());
                return 0;
            }
        }
    }

    @Command(name = "whoami", description = "Show current actor")
    static class WhoAmICommand implements Callable<Integer> {
        @ParentCommand private RbacCommand rbacCommand;

        @Override
        public Integer call() {
            String actor = CliSupport.currentActor();
            System.out.println("Actor: " + actor);
            List<RoleBinding> bindings = rbacCommand.parent.createContext().accessControlService().listRoleBindings().stream()
                    .filter(binding -> binding.actor().equalsIgnoreCase(actor))
                    .toList();
            if (bindings.isEmpty()) {
                System.out.println("Roles: none");
            } else {
                for (RoleBinding binding : bindings) {
                    System.out.printf("- %s [%s:%s]%n",
                            binding.role().name(),
                            binding.scopeType(),
                            binding.scopeName() == null ? "-" : binding.scopeName());
                }
            }
            return 0;
        }
    }

    private static RbacRole parseRole(String raw) {
        CliSupport.requireNonBlank(raw, "role");
        return RbacRole.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
