package com.drewdrew1.infra.persistence;

import com.drewdrew1.core.model.AllocationAffinity;
import com.drewdrew1.core.model.GpuPartitionRecord;
import com.drewdrew1.core.model.QueueEntry;
import com.drewdrew1.core.model.QuotaAlertPolicy;
import com.drewdrew1.core.model.QuotaPolicy;
import com.drewdrew1.core.repository.GovernanceRepository;

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
import java.util.List;
import java.util.Optional;

/** Stores queue, quota, alert, and logical partition records in SQLite. */
public class SqliteGovernanceRepository implements GovernanceRepository {
    private final Path dbPath;
    private boolean initialized;

    public SqliteGovernanceRepository(Path dbPath) {
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
                    CREATE TABLE IF NOT EXISTS queue_entries (
                      id TEXT PRIMARY KEY,
                      owner TEXT NOT NULL,
                      tenant TEXT,
                      gpu_count INTEGER NOT NULL,
                      model_filter TEXT,
                      min_vram_mb INTEGER,
                      exclusive_node INTEGER NOT NULL,
                      requested_hours INTEGER NOT NULL,
                      priority INTEGER NOT NULL,
                      preemptible INTEGER NOT NULL,
                      affinity TEXT NOT NULL,
                      label_selector TEXT,
                      status TEXT NOT NULL,
                      reason TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS gpu_partitions (
                      id TEXT PRIMARY KEY,
                      node_hostname TEXT NOT NULL,
                      gpu_device_id TEXT NOT NULL,
                      gpu_model TEXT NOT NULL,
                      profile TEXT NOT NULL,
                      status TEXT NOT NULL,
                      created_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS quota_policies (
                      name TEXT PRIMARY KEY,
                      max_gpus INTEGER,
                      max_vram_mb INTEGER,
                      max_lease_hours INTEGER,
                      burst_allow INTEGER NOT NULL,
                      updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS quota_alert_policies (
                      name TEXT PRIMARY KEY,
                      thresholds TEXT NOT NULL,
                      updated_at TEXT NOT NULL
                    )
                    """);
            initialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize governance schema", e);
        }
    }

    @Override
    public QueueEntry createQueueEntry(QueueEntry entry) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO queue_entries(
                       id, owner, tenant, gpu_count, model_filter, min_vram_mb, exclusive_node,
                       requested_hours, priority, preemptible, affinity, label_selector,
                       status, reason, created_at, updated_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, entry.id());
            statement.setString(2, entry.owner());
            statement.setString(3, entry.tenant());
            statement.setInt(4, entry.gpuCount());
            statement.setString(5, entry.modelFilter());
            if (entry.minVramMb() == null) {
                statement.setNull(6, Types.BIGINT);
            } else {
                statement.setLong(6, entry.minVramMb());
            }
            statement.setInt(7, entry.exclusiveNode() ? 1 : 0);
            statement.setInt(8, entry.requestedHours());
            statement.setInt(9, entry.priority());
            statement.setInt(10, entry.preemptible() ? 1 : 0);
            statement.setString(11, entry.affinity().name());
            statement.setString(12, entry.labelSelector());
            statement.setString(13, entry.status());
            statement.setString(14, entry.reason());
            statement.setString(15, entry.createdAt().toString());
            statement.setString(16, entry.updatedAt().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create queue entry", e);
        }
        return findQueueEntry(entry.id()).orElseThrow();
    }

    @Override
    public List<QueueEntry> listQueueEntries() {
        initialize();
        List<QueueEntry> entries = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT *
                     FROM queue_entries
                     ORDER BY priority DESC, created_at ASC
                     """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                entries.add(mapQueueEntry(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load queue entries", e);
        }
        return entries;
    }

    @Override
    public Optional<QueueEntry> findQueueEntry(String id) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM queue_entries WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapQueueEntry(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load queue entry", e);
        }
    }

    @Override
    public QueueEntry updateQueuePriority(String id, int newPriority) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE queue_entries
                     SET priority = ?, updated_at = ?
                     WHERE id = ?
                     """)) {
            statement.setInt(1, newPriority);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update queue priority", e);
        }
        return findQueueEntry(id).orElseThrow();
    }

    @Override
    public GpuPartitionRecord createPartition(GpuPartitionRecord partitionRecord) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO gpu_partitions(id, node_hostname, gpu_device_id, gpu_model, profile, status, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, partitionRecord.id());
            statement.setString(2, partitionRecord.nodeHostname());
            statement.setString(3, partitionRecord.gpuDeviceId());
            statement.setString(4, partitionRecord.gpuModel());
            statement.setString(5, partitionRecord.profile());
            statement.setString(6, partitionRecord.status());
            statement.setString(7, partitionRecord.createdAt().toString());
            statement.executeUpdate();
            return partitionRecord;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create GPU partition record", e);
        }
    }

    @Override
    public List<GpuPartitionRecord> listPartitions() {
        initialize();
        List<GpuPartitionRecord> partitions = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT *
                     FROM gpu_partitions
                     ORDER BY created_at DESC, gpu_device_id
                     """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                partitions.add(new GpuPartitionRecord(
                        rs.getString("id"),
                        rs.getString("node_hostname"),
                        rs.getString("gpu_device_id"),
                        rs.getString("gpu_model"),
                        rs.getString("profile"),
                        rs.getString("status"),
                        Instant.parse(rs.getString("created_at"))
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load GPU partitions", e);
        }
        return partitions;
    }

    @Override
    public int deletePartitionById(String id) {
        initialize();
        return deletePartitions("DELETE FROM gpu_partitions WHERE id = ?", id);
    }

    @Override
    public int deletePartitionsByGpu(String gpuId) {
        initialize();
        return deletePartitions("DELETE FROM gpu_partitions WHERE gpu_device_id = ?", gpuId);
    }

    @Override
    public void upsertQuotaPolicy(QuotaPolicy policy) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO quota_policies(name, max_gpus, max_vram_mb, max_lease_hours, burst_allow, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT(name) DO UPDATE SET
                       max_gpus = excluded.max_gpus,
                       max_vram_mb = excluded.max_vram_mb,
                       max_lease_hours = excluded.max_lease_hours,
                       burst_allow = excluded.burst_allow,
                       updated_at = excluded.updated_at
                     """)) {
            statement.setString(1, policy.name());
            if (policy.maxGpus() == null) {
                statement.setNull(2, Types.INTEGER);
            } else {
                statement.setInt(2, policy.maxGpus());
            }
            if (policy.maxVramMb() == null) {
                statement.setNull(3, Types.BIGINT);
            } else {
                statement.setLong(3, policy.maxVramMb());
            }
            if (policy.maxLeaseHours() == null) {
                statement.setNull(4, Types.INTEGER);
            } else {
                statement.setInt(4, policy.maxLeaseHours());
            }
            statement.setInt(5, policy.burstAllow() ? 1 : 0);
            statement.setString(6, policy.updatedAt().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save quota policy", e);
        }
    }

    @Override
    public List<QuotaPolicy> listQuotaPolicies() {
        initialize();
        List<QuotaPolicy> policies = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM quota_policies ORDER BY name");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                policies.add(new QuotaPolicy(
                        rs.getString("name"),
                        getInteger(rs, "max_gpus"),
                        getLong(rs, "max_vram_mb"),
                        getInteger(rs, "max_lease_hours"),
                        rs.getInt("burst_allow") == 1,
                        Instant.parse(rs.getString("updated_at"))
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list quota policies", e);
        }
        return policies;
    }

    @Override
    public Optional<QuotaPolicy> findQuotaPolicy(String name) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM quota_policies WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new QuotaPolicy(
                        rs.getString("name"),
                        getInteger(rs, "max_gpus"),
                        getLong(rs, "max_vram_mb"),
                        getInteger(rs, "max_lease_hours"),
                        rs.getInt("burst_allow") == 1,
                        Instant.parse(rs.getString("updated_at"))
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find quota policy", e);
        }
    }

    @Override
    public void upsertQuotaAlertPolicy(QuotaAlertPolicy alertPolicy) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO quota_alert_policies(name, thresholds, updated_at)
                     VALUES (?, ?, ?)
                     ON CONFLICT(name) DO UPDATE SET
                       thresholds = excluded.thresholds,
                       updated_at = excluded.updated_at
                     """)) {
            statement.setString(1, alertPolicy.name());
            statement.setString(2, joinThresholds(alertPolicy.thresholds()));
            statement.setString(3, alertPolicy.updatedAt().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save quota alert policy", e);
        }
    }

    @Override
    public List<QuotaAlertPolicy> listQuotaAlertPolicies() {
        initialize();
        List<QuotaAlertPolicy> policies = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM quota_alert_policies ORDER BY name");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                policies.add(new QuotaAlertPolicy(
                        rs.getString("name"),
                        parseThresholds(rs.getString("thresholds")),
                        Instant.parse(rs.getString("updated_at"))
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list quota alert policies", e);
        }
        return policies;
    }

    @Override
    public Optional<QuotaAlertPolicy> findQuotaAlertPolicy(String name) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM quota_alert_policies WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new QuotaAlertPolicy(
                        rs.getString("name"),
                        parseThresholds(rs.getString("thresholds")),
                        Instant.parse(rs.getString("updated_at"))
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find quota alert policy", e);
        }
    }

    private QueueEntry mapQueueEntry(ResultSet rs) throws SQLException {
        return new QueueEntry(
                rs.getString("id"),
                rs.getString("owner"),
                rs.getString("tenant"),
                rs.getInt("gpu_count"),
                rs.getString("model_filter"),
                getLong(rs, "min_vram_mb"),
                rs.getInt("exclusive_node") == 1,
                rs.getInt("requested_hours"),
                rs.getInt("priority"),
                rs.getInt("preemptible") == 1,
                AllocationAffinity.valueOf(rs.getString("affinity")),
                rs.getString("label_selector"),
                rs.getString("status"),
                rs.getString("reason"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private int deletePartitions(String sql, String selector) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, selector);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete partition records", e);
        }
    }

    private String joinThresholds(List<Integer> thresholds) {
        List<String> values = new ArrayList<>();
        for (Integer threshold : thresholds) {
            values.add(Integer.toString(threshold));
        }
        return String.join(",", values);
    }

    private List<Integer> parseThresholds(String raw) {
        List<Integer> thresholds = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return thresholds;
        }
        for (String part : raw.split(",")) {
            thresholds.add(Integer.parseInt(part.trim()));
        }
        return thresholds;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    private static Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
