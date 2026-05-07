package com.drewdrew1.cli;

import com.drewdrew1.core.detector.GpuDetector;
import com.drewdrew1.core.repository.AuditRepository;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.core.repository.InventoryRepository;
import com.drewdrew1.core.service.AllocationService;
import com.drewdrew1.core.service.AuditService;
import com.drewdrew1.core.service.CapabilityResolver;
import com.drewdrew1.core.service.InventoryService;
import com.drewdrew1.core.service.NodeInfoProvider;
import com.drewdrew1.core.service.RemoteSystemInfoService;
import com.drewdrew1.core.service.SystemInfoService;
import com.drewdrew1.infra.detector.AmdDetector;
import com.drewdrew1.infra.detector.IntelDetector;
import com.drewdrew1.infra.detector.NvidiaDetector;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.LocalCommandExecutor;
import com.drewdrew1.infra.executor.SshCommandExecutor;
import com.drewdrew1.infra.output.TablePrinter;
import com.drewdrew1.infra.persistence.SqliteAllocationRepository;
import com.drewdrew1.infra.persistence.SqliteAuditRepository;
import com.drewdrew1.infra.persistence.SqliteInventoryRepository;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Lazily builds and caches shared services used by CLI commands. */
public class AppContext {
    private final Path dbPath;
    private final Duration commandTimeout;
    private InventoryRepository inventoryRepository;
    private AllocationRepository allocationRepository;
    private AuditRepository auditRepository;
    private TablePrinter tablePrinter;
    private SystemInfoService systemInfoService;
    private CommandExecutor localCommandExecutor;
    private AuditService auditService;
    private AllocationService allocationService;

    public AppContext(Path dbPath, Duration commandTimeout) {
        this.dbPath = dbPath;
        this.commandTimeout = commandTimeout;
    }

    public InventoryService inventoryService() {
        return inventoryService(commandExecutor(), new SystemInfoService());
    }

    public InventoryService inventoryService(CommandExecutor executor, NodeInfoProvider nodeInfoProvider) {
        CapabilityResolver capabilityResolver = new CapabilityResolver();
        List<GpuDetector> detectors = List.of(
                new NvidiaDetector(executor, capabilityResolver),
                new AmdDetector(executor, capabilityResolver),
                new IntelDetector(executor, capabilityResolver)
        );
        InventoryRepository repository = new SqliteInventoryRepository(dbPath);
        return new InventoryService(repository, detectors, nodeInfoProvider);
    }

    public InventoryRepository inventoryRepository() {
        if (inventoryRepository == null) {
            inventoryRepository = new SqliteInventoryRepository(dbPath);
        }
        return inventoryRepository;
    }

    public TablePrinter tablePrinter() {
        if (tablePrinter == null) {
            tablePrinter = new TablePrinter();
        }
        return tablePrinter;
    }

    public AllocationRepository allocationRepository() {
        if (allocationRepository == null) {
            allocationRepository = new SqliteAllocationRepository(dbPath);
        }
        return allocationRepository;
    }

    public AllocationService allocationService() {
        if (allocationService == null) {
            allocationService = new AllocationService(inventoryRepository(), allocationRepository());
        }
        return allocationService;
    }

    public AuditRepository auditRepository() {
        if (auditRepository == null) {
            auditRepository = new SqliteAuditRepository(dbPath);
        }
        return auditRepository;
    }

    public AuditService auditService() {
        if (auditService == null) {
            auditService = new AuditService(auditRepository());
        }
        return auditService;
    }

    public SystemInfoService systemInfoService() {
        if (systemInfoService == null) {
            systemInfoService = new SystemInfoService();
        }
        return systemInfoService;
    }

    public CommandExecutor commandExecutor() {
        if (localCommandExecutor == null) {
            localCommandExecutor = new LocalCommandExecutor(commandTimeout);
        }
        return localCommandExecutor;
    }

    public CommandExecutor sshCommandExecutor(String address, String sshUser) {
        return new SshCommandExecutor(commandTimeout, address, sshUser);
    }

    public InventoryService remoteInventoryService(String address, String sshUser) {
        CommandExecutor executor = sshCommandExecutor(address, sshUser);
        return inventoryService(executor, new RemoteSystemInfoService(executor));
    }

    public Path dbPath() {
        return dbPath;
    }

    public Duration commandTimeout() {
        return commandTimeout;
    }
}
