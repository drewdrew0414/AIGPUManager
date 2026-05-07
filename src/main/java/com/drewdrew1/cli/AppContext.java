package com.drewdrew1.cli;

import com.drewdrew1.core.detector.GpuDetector;
import com.drewdrew1.core.repository.InventoryRepository;
import com.drewdrew1.core.service.CapabilityResolver;
import com.drewdrew1.core.service.InventoryService;
import com.drewdrew1.core.service.SystemInfoService;
import com.drewdrew1.infra.detector.AmdDetector;
import com.drewdrew1.infra.detector.IntelDetector;
import com.drewdrew1.infra.detector.NvidiaDetector;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.LocalCommandExecutor;
import com.drewdrew1.infra.output.TablePrinter;
import com.drewdrew1.infra.persistence.SqliteInventoryRepository;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class AppContext {
    private final Path dbPath;
    private final Duration commandTimeout;

    public AppContext(Path dbPath, Duration commandTimeout) {
        this.dbPath = dbPath;
        this.commandTimeout = commandTimeout;
    }

    public InventoryService inventoryService() {
        CapabilityResolver capabilityResolver = new CapabilityResolver();
        CommandExecutor executor = new LocalCommandExecutor(commandTimeout);
        List<GpuDetector> detectors = List.of(
                new NvidiaDetector(executor, capabilityResolver),
                new AmdDetector(executor, capabilityResolver),
                new IntelDetector(executor, capabilityResolver)
        );
        InventoryRepository repository = new SqliteInventoryRepository(dbPath);
        return new InventoryService(repository, detectors, new SystemInfoService());
    }

    public InventoryRepository inventoryRepository() {
        return new SqliteInventoryRepository(dbPath);
    }

    public TablePrinter tablePrinter() {
        return new TablePrinter();
    }
}
