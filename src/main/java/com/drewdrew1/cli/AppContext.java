package com.drewdrew1.cli;

import com.drewdrew1.core.config.GpumConfig;
import com.drewdrew1.core.detector.GpuDetector;
import com.drewdrew1.core.repository.AuditRepository;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.core.repository.GovernanceRepository;
import com.drewdrew1.core.repository.InventoryRepository;
import com.drewdrew1.core.repository.LogRepository;
import com.drewdrew1.core.repository.OpsRepository;
import com.drewdrew1.core.repository.RuntimeRepository;
import com.drewdrew1.core.service.AllocationService;
import com.drewdrew1.core.service.AccessControlService;
import com.drewdrew1.core.service.AuditService;
import com.drewdrew1.core.service.CapabilityResolver;
import com.drewdrew1.core.service.ContainerReconcileService;
import com.drewdrew1.core.service.EnterpriseOpsService;
import com.drewdrew1.core.service.FleetAnalysisService;
import com.drewdrew1.core.service.GovernanceService;
import com.drewdrew1.core.service.GpuControlService;
import com.drewdrew1.core.service.GpuProcessService;
import com.drewdrew1.core.service.HealthScoringService;
import com.drewdrew1.core.service.IntegrationService;
import com.drewdrew1.core.service.InventoryService;
import com.drewdrew1.core.service.LogService;
import com.drewdrew1.core.service.NativeGpuTelemetryService;
import com.drewdrew1.core.service.NodeInfoProvider;
import com.drewdrew1.core.service.PartitionControlService;
import com.drewdrew1.core.service.PrometheusExportService;
import com.drewdrew1.core.service.RemoteGpuControlService;
import com.drewdrew1.core.service.RemoteSystemInfoService;
import com.drewdrew1.core.service.RuntimeWorkerService;
import com.drewdrew1.core.service.SchedulingEngineService;
import com.drewdrew1.core.service.SystemInfoService;
import com.drewdrew1.core.service.WorkloadProfileService;
import com.drewdrew1.infra.detector.AmdDetector;
import com.drewdrew1.infra.detector.IntelDetector;
import com.drewdrew1.infra.detector.NvidiaDetector;
import com.drewdrew1.infra.executor.CommandExecutor;
import com.drewdrew1.infra.executor.LocalCommandExecutor;
import com.drewdrew1.infra.executor.SshCommandExecutor;
import com.drewdrew1.infra.output.TablePrinter;
import com.drewdrew1.infra.persistence.SqliteAllocationRepository;
import com.drewdrew1.infra.persistence.SqliteAuditRepository;
import com.drewdrew1.infra.persistence.SqliteGovernanceRepository;
import com.drewdrew1.infra.persistence.SqliteInventoryRepository;
import com.drewdrew1.infra.persistence.SqliteLogRepository;
import com.drewdrew1.infra.persistence.SqliteOpsRepository;
import com.drewdrew1.infra.persistence.SqliteRuntimeRepository;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Lazily builds and caches shared services used by CLI commands. */
public class AppContext {
    private final Path dbPath;
    private final Duration commandTimeout;
    private final GpumConfig config;
    private InventoryRepository inventoryRepository;
    private AllocationRepository allocationRepository;
    private AuditRepository auditRepository;
    private GovernanceRepository governanceRepository;
    private LogRepository logRepository;
    private OpsRepository opsRepository;
    private RuntimeRepository runtimeRepository;
    private TablePrinter tablePrinter;
    private SystemInfoService systemInfoService;
    private CommandExecutor localCommandExecutor;
    private AuditService auditService;
    private AllocationService allocationService;
    private LogService logService;
    private IntegrationService integrationService;
    private GovernanceService governanceService;
    private GpuControlService gpuControlService;
    private AccessControlService accessControlService;
    private GpuProcessService gpuProcessService;
    private RemoteGpuControlService remoteGpuControlService;
    private PartitionControlService partitionControlService;
    private HealthScoringService healthScoringService;
    private PrometheusExportService prometheusExportService;
    private WorkloadProfileService workloadProfileService;
    private NativeGpuTelemetryService nativeGpuTelemetryService;
    private RuntimeWorkerService runtimeWorkerService;
    private SchedulingEngineService schedulingEngineService;
    private ContainerReconcileService containerReconcileService;
    private EnterpriseOpsService enterpriseOpsService;
    private FleetAnalysisService fleetAnalysisService;

    public AppContext(Path dbPath, Duration commandTimeout, GpumConfig config) {
        this.dbPath = dbPath;
        this.commandTimeout = commandTimeout;
        this.config = config;
    }

    public InventoryService inventoryService() {
        return inventoryService(commandExecutor(), new SystemInfoService());
    }

    public InventoryService inventoryService(CommandExecutor executor, NodeInfoProvider nodeInfoProvider) {
        CapabilityResolver capabilityResolver = new CapabilityResolver();
        List<GpuDetector> detectors = List.of(
                new NvidiaDetector(executor, capabilityResolver, config.getTools().getNvidiaSmi()),
                new AmdDetector(executor, capabilityResolver, config.getTools().getAmdSmi(), config.getTools().getRocmSmi()),
                new IntelDetector(executor, capabilityResolver, config.getTools().getXpuSmi(), config.getTools().getPowershell())
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

    public GovernanceRepository governanceRepository() {
        if (governanceRepository == null) {
            governanceRepository = new SqliteGovernanceRepository(dbPath);
        }
        return governanceRepository;
    }

    public LogRepository logRepository() {
        if (logRepository == null) {
            logRepository = new SqliteLogRepository(dbPath);
        }
        return logRepository;
    }

    public RuntimeRepository runtimeRepository() {
        if (runtimeRepository == null) {
            runtimeRepository = new SqliteRuntimeRepository(dbPath);
        }
        return runtimeRepository;
    }

    public OpsRepository opsRepository() {
        if (opsRepository == null) {
            opsRepository = new SqliteOpsRepository(dbPath);
        }
        return opsRepository;
    }

    public LogService logService() {
        if (logService == null) {
            logService = new LogService(logRepository());
        }
        return logService;
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
        return new SshCommandExecutor(commandTimeout, address, sshUser, config.getTools().getSsh());
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

    public GpumConfig config() {
        return config;
    }

    public IntegrationService integrationService() {
        if (integrationService == null) {
            integrationService = new IntegrationService(commandExecutor(), config);
        }
        return integrationService;
    }

    public GovernanceService governanceService() {
        if (governanceService == null) {
            governanceService = new GovernanceService(governanceRepository(), inventoryRepository(), allocationRepository());
        }
        return governanceService;
    }

    public GpuControlService gpuControlService() {
        if (gpuControlService == null) {
            gpuControlService = new GpuControlService(
                    commandExecutor(),
                    inventoryRepository(),
                    allocationRepository(),
                    systemInfoService(),
                    config
            );
        }
        return gpuControlService;
    }

    public AccessControlService accessControlService() {
        if (accessControlService == null) {
            accessControlService = new AccessControlService(governanceRepository());
        }
        return accessControlService;
    }

    public GpuProcessService gpuProcessService() {
        if (gpuProcessService == null) {
            gpuProcessService = new GpuProcessService(commandExecutor(), config, systemInfoService());
        }
        return gpuProcessService;
    }

    public RemoteGpuControlService remoteGpuControlService() {
        if (remoteGpuControlService == null) {
            remoteGpuControlService = new RemoteGpuControlService(
                    inventoryRepository(),
                    config,
                    this::sshCommandExecutor
            );
        }
        return remoteGpuControlService;
    }

    public PartitionControlService partitionControlService() {
        if (partitionControlService == null) {
            partitionControlService = new PartitionControlService(
                    governanceRepository(),
                    inventoryRepository(),
                    allocationRepository(),
                    commandExecutor(),
                    systemInfoService(),
                    gpuProcessService(),
                    config
            );
        }
        return partitionControlService;
    }

    public HealthScoringService healthScoringService() {
        if (healthScoringService == null) {
            healthScoringService = new HealthScoringService(inventoryRepository(), config.getMonitoring());
        }
        return healthScoringService;
    }

    public PrometheusExportService prometheusExportService() {
        if (prometheusExportService == null) {
            prometheusExportService = new PrometheusExportService(
                    inventoryRepository(),
                    allocationRepository(),
                    healthScoringService()
            );
        }
        return prometheusExportService;
    }

    public WorkloadProfileService workloadProfileService() {
        if (workloadProfileService == null) {
            workloadProfileService = new WorkloadProfileService();
        }
        return workloadProfileService;
    }

    public NativeGpuTelemetryService nativeGpuTelemetryService() {
        if (nativeGpuTelemetryService == null) {
            nativeGpuTelemetryService = new NativeGpuTelemetryService();
        }
        return nativeGpuTelemetryService;
    }

    public RuntimeWorkerService runtimeWorkerService() {
        if (runtimeWorkerService == null) {
            runtimeWorkerService = new RuntimeWorkerService(runtimeRepository(), allocationRepository());
        }
        return runtimeWorkerService;
    }

    public SchedulingEngineService schedulingEngineService() {
        if (schedulingEngineService == null) {
            schedulingEngineService = new SchedulingEngineService(inventoryRepository(), allocationRepository());
        }
        return schedulingEngineService;
    }

    public ContainerReconcileService containerReconcileService() {
        if (containerReconcileService == null) {
            containerReconcileService = new ContainerReconcileService(commandExecutor(), allocationRepository(), config);
        }
        return containerReconcileService;
    }

    public EnterpriseOpsService enterpriseOpsService() {
        if (enterpriseOpsService == null) {
            enterpriseOpsService = new EnterpriseOpsService(
                    opsRepository(),
                    allocationRepository(),
                    runtimeWorkerService(),
                    commandExecutor()
            );
        }
        return enterpriseOpsService;
    }

    public FleetAnalysisService fleetAnalysisService() {
        if (fleetAnalysisService == null) {
            fleetAnalysisService = new FleetAnalysisService(
                    inventoryRepository(),
                    allocationRepository(),
                    opsRepository(),
                    runtimeRepository(),
                    healthScoringService(),
                    config
            );
        }
        return fleetAnalysisService;
    }
}
