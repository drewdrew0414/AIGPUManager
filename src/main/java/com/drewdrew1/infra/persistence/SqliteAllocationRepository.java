package com.drewdrew1.infra.persistence;

import com.drewdrew1.core.model.AllocationAffinity;
import com.drewdrew1.core.model.AllocationDevice;
import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.AllocationStatus;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.repository.AllocationRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Stores allocation and claim state in a local SQLite database. */
public class SqliteAllocationRepository implements AllocationRepository {
    private final Path dbPath;
    private boolean initialized;

    public SqliteAllocationRepository(Path dbPath) {
        this.dbPath = dbPath;
    }

    @Override
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        try {
            Path parent = dbPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create database directory", e);
        }

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS allocations (
                      id TEXT PRIMARY KEY,
                      owner TEXT NOT NULL,
                      tenant TEXT,
                      status TEXT NOT NULL,
                      exclusive_node INTEGER NOT NULL,
                      priority INTEGER NOT NULL,
                      preemptible INTEGER NOT NULL,
                      affinity TEXT NOT NULL,
                      requested_gpu_count INTEGER NOT NULL,
                      model_filter TEXT,
                      min_vram_mb INTEGER,
                      label_selector TEXT,
                      primary_node_hostname TEXT,
                      created_at TEXT NOT NULL,
                      expires_at TEXT NOT NULL,
                      released_at TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS allocation_gpus (
                      allocation_id TEXT NOT NULL,
                      node_hostname TEXT NOT NULL,
                      vendor TEXT NOT NULL,
                      device_id TEXT,
                      uuid TEXT,
                      model TEXT,
                      pci_bus_id TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS active_gpu_claims (
                      gpu_key TEXT PRIMARY KEY,
                      allocation_id TEXT NOT NULL,
                      node_hostname TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS exclusive_node_claims (
                      node_hostname TEXT PRIMARY KEY,
                      allocation_id TEXT NOT NULL
                    )
                    """);
            initialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize allocation schema", e);
        }
    }

    @Override
    public AllocationRecord create(AllocationRecord record) {
        initialize();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insertAllocation = connection.prepareStatement("""
                    INSERT INTO allocations(
                      id, owner, tenant, status, exclusive_node, priority, preemptible, affinity,
                      requested_gpu_count, model_filter, min_vram_mb, label_selector,
                      primary_node_hostname, created_at, expires_at, released_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
                 PreparedStatement insertDevice = connection.prepareStatement("""
                    INSERT INTO allocation_gpus(
                      allocation_id, node_hostname, vendor, device_id, uuid, model, pci_bus_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """);
                 PreparedStatement insertGpuClaim = connection.prepareStatement("""
                    INSERT INTO active_gpu_claims(gpu_key, allocation_id, node_hostname)
                    VALUES (?, ?, ?)
                    """);
                 PreparedStatement insertExclusiveNodeClaim = connection.prepareStatement("""
                    INSERT INTO exclusive_node_claims(node_hostname, allocation_id)
                    VALUES (?, ?)
                    """);
                 PreparedStatement selectExclusiveNodeClaim = connection.prepareStatement("""
                    SELECT node_hostname
                    FROM exclusive_node_claims
                    WHERE node_hostname = ?
                    """)) {
                insertAllocation.setString(1, record.id());
                insertAllocation.setString(2, record.owner());
                insertAllocation.setString(3, record.tenant());
                insertAllocation.setString(4, record.status().name());
                insertAllocation.setInt(5, record.exclusiveNode() ? 1 : 0);
                insertAllocation.setInt(6, record.priority());
                insertAllocation.setInt(7, record.preemptible() ? 1 : 0);
                insertAllocation.setString(8, record.affinity().name());
                insertAllocation.setInt(9, record.requestedGpuCount());
                insertAllocation.setString(10, record.modelFilter());
                if (record.minVramMb() == null) {
                    insertAllocation.setNull(11, Types.BIGINT);
                } else {
                    insertAllocation.setLong(11, record.minVramMb());
                }
                insertAllocation.setString(12, record.labelSelector());
                insertAllocation.setString(13, record.primaryNodeHostname());
                insertAllocation.setString(14, record.createdAt().toString());
                insertAllocation.setString(15, record.expiresAt().toString());
                if (record.releasedAt() == null) {
                    insertAllocation.setNull(16, Types.VARCHAR);
                } else {
                    insertAllocation.setString(16, record.releasedAt().toString());
                }
                insertAllocation.executeUpdate();

                for (AllocationDevice device : record.devices()) {
                    insertDevice.setString(1, record.id());
                    insertDevice.setString(2, device.nodeHostname());
                    insertDevice.setString(3, device.vendor().name());
                    insertDevice.setString(4, device.deviceId());
                    insertDevice.setString(5, device.uuid());
                    insertDevice.setString(6, device.model());
                    insertDevice.setString(7, device.pciBusId());
                    insertDevice.addBatch();
                }
                insertDevice.executeBatch();

                Set<String> touchedNodes = new LinkedHashSet<>();
                for (AllocationDevice device : record.devices()) {
                    touchedNodes.add(device.nodeHostname());
                    String gpuKey = device.uuid() != null && !device.uuid().isBlank()
                            ? "uuid:" + device.uuid()
                            : device.nodeHostname() + ":" + device.deviceId();
                    insertGpuClaim.setString(1, gpuKey);
                    insertGpuClaim.setString(2, record.id());
                    insertGpuClaim.setString(3, device.nodeHostname());
                    insertGpuClaim.addBatch();
                }

                if (!record.exclusiveNode()) {
                    for (String node : touchedNodes) {
                        selectExclusiveNodeClaim.setString(1, node);
                        try (ResultSet rs = selectExclusiveNodeClaim.executeQuery()) {
                            if (rs.next()) {
                                throw new IllegalStateException("Node is exclusively reserved: " + node);
                            }
                        }
                    }
                }

                try {
                    insertGpuClaim.executeBatch();
                } catch (SQLException e) {
                    throw new IllegalStateException("One or more GPUs are already allocated.", e);
                }

                if (record.exclusiveNode()) {
                    try {
                        for (String node : touchedNodes) {
                            insertExclusiveNodeClaim.setString(1, node);
                            insertExclusiveNodeClaim.setString(2, record.id());
                            insertExclusiveNodeClaim.addBatch();
                        }
                        insertExclusiveNodeClaim.executeBatch();
                    } catch (SQLException e) {
                        throw new IllegalStateException("Exclusive node reservation failed due to an active claim.", e);
                    }
                }
                connection.commit();
            } catch (IllegalStateException e) {
                connection.rollback();
                throw e;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create allocation", e);
        }
        return findById(record.id())
                .orElseThrow(() -> new IllegalStateException("Allocation was inserted but could not be reloaded: " + record.id()));
    }

    @Override
    public List<AllocationRecord> list() {
        initialize();
        return loadAllocations(null);
    }

    @Override
    public Optional<AllocationRecord> findById(String id) {
        initialize();
        List<AllocationRecord> records = loadAllocations(id);
        return records.isEmpty() ? Optional.empty() : Optional.of(records.get(0));
    }

    @Override
    public List<AllocationRecord> listActive() {
        initialize();
        return loadAllocationsByStatus(AllocationStatus.ACTIVE);
    }

    @Override
    public void updateStatus(String id, AllocationStatus status, Instant releasedAt) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE allocations
                     SET status = ?, released_at = ?
                     WHERE id = ?
                     """);
             PreparedStatement deleteGpuClaims = connection.prepareStatement("""
                     DELETE FROM active_gpu_claims
                     WHERE allocation_id = ?
                     """);
             PreparedStatement deleteNodeClaims = connection.prepareStatement("""
                     DELETE FROM exclusive_node_claims
                     WHERE allocation_id = ?
                     """)) {
            connection.setAutoCommit(false);
            statement.setString(1, status.name());
            if (releasedAt == null) {
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(2, releasedAt.toString());
            }
            statement.setString(3, id);
            statement.executeUpdate();
            if (status == AllocationStatus.RELEASED || status == AllocationStatus.EXPIRED) {
                deleteGpuClaims.setString(1, id);
                deleteGpuClaims.executeUpdate();
                deleteNodeClaims.setString(1, id);
                deleteNodeClaims.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update allocation status", e);
        }
    }

    @Override
    public void updateExpiresAt(String id, Instant expiresAt) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE allocations
                     SET expires_at = ?
                     WHERE id = ?
                     """)) {
            statement.setString(1, expiresAt.toString());
            statement.setString(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update allocation expiry", e);
        }
    }

    @Override
    public int markExpiredAllocations(Instant now) {
        initialize();
        List<String> expiredIds = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id
                     FROM allocations
                     WHERE status = ? AND expires_at < ?
                     """)) {
            statement.setString(1, AllocationStatus.ACTIVE.name());
            statement.setString(2, now.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    expiredIds.add(rs.getString("id"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find expired allocations", e);
        }

        for (String id : expiredIds) {
            updateStatus(id, AllocationStatus.EXPIRED, now);
        }
        return expiredIds.size();
    }

    private List<AllocationRecord> loadAllocations(String allocationId) {
        Map<String, AllocationRecord> records = new LinkedHashMap<>();
        String sql = """
                SELECT a.id, a.owner, a.tenant, a.status, a.exclusive_node, a.priority, a.preemptible, a.affinity,
                       a.requested_gpu_count, a.model_filter, a.min_vram_mb, a.label_selector,
                       a.primary_node_hostname, a.created_at, a.expires_at, a.released_at,
                       g.node_hostname AS g_node_hostname, g.vendor, g.device_id, g.uuid, g.model, g.pci_bus_id
                FROM allocations a
                LEFT JOIN allocation_gpus g ON g.allocation_id = a.id
                %s
                ORDER BY a.created_at DESC, g.node_hostname, g.device_id
                """.formatted(allocationId == null ? "" : "WHERE a.id = ?");

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (allocationId != null) {
                statement.setString(1, allocationId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    mapAllocationRow(records, rs);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load allocations", e);
        }
        return new ArrayList<>(records.values());
    }

    private List<AllocationRecord> loadAllocationsByStatus(AllocationStatus status) {
        Map<String, AllocationRecord> records = new LinkedHashMap<>();
        String sql = """
                SELECT a.id, a.owner, a.tenant, a.status, a.exclusive_node, a.priority, a.preemptible, a.affinity,
                       a.requested_gpu_count, a.model_filter, a.min_vram_mb, a.label_selector,
                       a.primary_node_hostname, a.created_at, a.expires_at, a.released_at,
                       g.node_hostname AS g_node_hostname, g.vendor, g.device_id, g.uuid, g.model, g.pci_bus_id
                FROM allocations a
                LEFT JOIN allocation_gpus g ON g.allocation_id = a.id
                WHERE a.status = ?
                ORDER BY a.created_at DESC, g.node_hostname, g.device_id
                """;

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    mapAllocationRow(records, rs);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load allocations by status", e);
        }
        return new ArrayList<>(records.values());
    }

    private void mapAllocationRow(Map<String, AllocationRecord> records, ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        AllocationRecord existing = records.get(id);
        List<AllocationDevice> devices = existing == null ? new ArrayList<>() : new ArrayList<>(existing.devices());
        if (rs.getString("g_node_hostname") != null) {
            devices.add(new AllocationDevice(
                    rs.getString("g_node_hostname"),
                    GpuVendor.valueOf(rs.getString("vendor")),
                    rs.getString("device_id"),
                    rs.getString("uuid"),
                    rs.getString("model"),
                    rs.getString("pci_bus_id")
            ));
        }
        records.put(id, new AllocationRecord(
                id,
                rs.getString("owner"),
                rs.getString("tenant"),
                AllocationStatus.valueOf(rs.getString("status")),
                rs.getInt("exclusive_node") == 1,
                rs.getInt("priority"),
                rs.getInt("preemptible") == 1,
                AllocationAffinity.valueOf(rs.getString("affinity")),
                rs.getInt("requested_gpu_count"),
                rs.getString("model_filter"),
                getLong(rs, "min_vram_mb"),
                rs.getString("label_selector"),
                rs.getString("primary_node_hostname"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("expires_at")),
                rs.getString("released_at") == null ? null : Instant.parse(rs.getString("released_at")),
                List.copyOf(devices)
        ));
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    private static Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
