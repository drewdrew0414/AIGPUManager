package com.drewdrew1.infra.persistence;

import com.drewdrew1.core.model.RuntimeEvent;
import com.drewdrew1.core.model.RuntimeWorkerRecord;
import com.drewdrew1.core.repository.RuntimeRepository;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores runtime worker registry and events in SQLite. */
public class SqliteRuntimeRepository implements RuntimeRepository {
    private final Path dbPath;
    private boolean initialized;

    public SqliteRuntimeRepository(Path dbPath) {
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
            throw new IllegalStateException("Failed to create runtime database directory", e);
        }
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_workers (
                      id TEXT PRIMARY KEY,
                      allocation_id TEXT,
                      tenant TEXT,
                      owner TEXT NOT NULL,
                      command TEXT NOT NULL,
                      working_directory TEXT,
                      environment TEXT,
                      checkpoint_command TEXT,
                      restore_command TEXT,
                      oom_recovery_command TEXT,
                      pid INTEGER,
                      status TEXT NOT NULL,
                      restart_count INTEGER NOT NULL,
                      max_restarts INTEGER NOT NULL,
                      max_lifetime_minutes INTEGER NOT NULL,
                      memory_restart_mb INTEGER,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      started_at TEXT
                    )
                    """);
            ensureColumn(connection, "runtime_workers", "oom_recovery_command", "TEXT");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_events (
                      id TEXT PRIMARY KEY,
                      worker_id TEXT,
                      event_type TEXT NOT NULL,
                      message TEXT NOT NULL,
                      created_at TEXT NOT NULL
                    )
                    """);
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            initialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize runtime schema", e);
        }
    }

    @Override
    public RuntimeWorkerRecord upsertWorker(RuntimeWorkerRecord worker) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO runtime_workers(
                       id, allocation_id, tenant, owner, command, working_directory, environment,
                       checkpoint_command, restore_command, oom_recovery_command, pid, status, restart_count, max_restarts,
                       max_lifetime_minutes, memory_restart_mb, created_at, updated_at, started_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET
                       allocation_id = excluded.allocation_id,
                       tenant = excluded.tenant,
                       owner = excluded.owner,
                       command = excluded.command,
                       working_directory = excluded.working_directory,
                       environment = excluded.environment,
                       checkpoint_command = excluded.checkpoint_command,
                       restore_command = excluded.restore_command,
                       oom_recovery_command = excluded.oom_recovery_command,
                       pid = excluded.pid,
                       status = excluded.status,
                       restart_count = excluded.restart_count,
                       max_restarts = excluded.max_restarts,
                       max_lifetime_minutes = excluded.max_lifetime_minutes,
                       memory_restart_mb = excluded.memory_restart_mb,
                       updated_at = excluded.updated_at,
                       started_at = excluded.started_at
                     """)) {
            bindWorker(statement, worker);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save runtime worker", e);
        }
        return findWorker(worker.id())
                .orElseThrow(() -> new IllegalStateException("Runtime worker was saved but could not be reloaded: " + worker.id()));
    }

    @Override
    public Optional<RuntimeWorkerRecord> findWorker(String id) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM runtime_workers WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapWorker(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find runtime worker", e);
        }
    }

    @Override
    public List<RuntimeWorkerRecord> listWorkers() {
        initialize();
        return queryWorkers("SELECT * FROM runtime_workers ORDER BY updated_at DESC, id", null);
    }

    @Override
    public List<RuntimeWorkerRecord> listWorkersByAllocation(String allocationId) {
        initialize();
        return queryWorkers("SELECT * FROM runtime_workers WHERE allocation_id = ? ORDER BY updated_at DESC, id", allocationId);
    }

    @Override
    public void updateWorker(RuntimeWorkerRecord worker) {
        upsertWorker(worker);
    }

    @Override
    public void deleteWorker(String id) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM runtime_workers WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete runtime worker", e);
        }
    }

    @Override
    public void appendEvent(RuntimeEvent event) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO runtime_events(id, worker_id, event_type, message, created_at)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, event.id());
            statement.setString(2, event.workerId());
            statement.setString(3, event.eventType());
            statement.setString(4, event.message());
            statement.setString(5, event.createdAt().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append runtime event", e);
        }
    }

    @Override
    public List<RuntimeEvent> listEvents(String workerId, int limit) {
        initialize();
        List<RuntimeEvent> events = new ArrayList<>();
        String sql = workerId == null
                ? "SELECT * FROM runtime_events ORDER BY created_at DESC LIMIT ?"
                : "SELECT * FROM runtime_events WHERE worker_id = ? ORDER BY created_at DESC LIMIT ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (workerId == null) {
                statement.setInt(1, limit);
            } else {
                statement.setString(1, workerId);
                statement.setInt(2, limit);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    events.add(new RuntimeEvent(
                            rs.getString("id"),
                            rs.getString("worker_id"),
                            rs.getString("event_type"),
                            rs.getString("message"),
                            Instant.parse(rs.getString("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list runtime events", e);
        }
        return events;
    }

    private List<RuntimeWorkerRecord> queryWorkers(String sql, String value) {
        List<RuntimeWorkerRecord> workers = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (value != null) {
                statement.setString(1, value);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    workers.add(mapWorker(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query runtime workers", e);
        }
        return workers;
    }

    private void ensureColumn(Connection connection, String table, String column, String type) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, column)) {
            if (rs.next()) {
                return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    private void bindWorker(PreparedStatement statement, RuntimeWorkerRecord worker) throws SQLException {
        statement.setString(1, worker.id());
        statement.setString(2, worker.allocationId());
        statement.setString(3, worker.tenant());
        statement.setString(4, worker.owner());
        statement.setString(5, worker.command());
        statement.setString(6, worker.workingDirectory());
        statement.setString(7, encodeEnv(worker.environment()));
        statement.setString(8, worker.checkpointCommand());
        statement.setString(9, worker.restoreCommand());
        statement.setString(10, worker.oomRecoveryCommand());
        if (worker.pid() == null) {
            statement.setNull(11, Types.BIGINT);
        } else {
            statement.setLong(11, worker.pid());
        }
        statement.setString(12, worker.status());
        statement.setInt(13, worker.restartCount());
        statement.setInt(14, worker.maxRestarts());
        statement.setInt(15, worker.maxLifetimeMinutes());
        if (worker.memoryRestartMb() == null) {
            statement.setNull(16, Types.BIGINT);
        } else {
            statement.setLong(16, worker.memoryRestartMb());
        }
        statement.setString(17, worker.createdAt().toString());
        statement.setString(18, worker.updatedAt().toString());
        statement.setString(19, worker.startedAt() == null ? null : worker.startedAt().toString());
    }

    private RuntimeWorkerRecord mapWorker(ResultSet rs) throws SQLException {
        return new RuntimeWorkerRecord(
                rs.getString("id"),
                rs.getString("allocation_id"),
                rs.getString("tenant"),
                rs.getString("owner"),
                rs.getString("command"),
                rs.getString("working_directory"),
                decodeEnv(rs.getString("environment")),
                rs.getString("checkpoint_command"),
                rs.getString("restore_command"),
                rs.getString("oom_recovery_command"),
                getLong(rs, "pid"),
                rs.getString("status"),
                rs.getInt("restart_count"),
                rs.getInt("max_restarts"),
                rs.getInt("max_lifetime_minutes"),
                getLong(rs, "memory_restart_mb"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                rs.getString("started_at") == null ? null : Instant.parse(rs.getString("started_at"))
        );
    }

    private String encodeEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            parts.add(entry.getKey().replace("\\", "\\\\").replace("=", "\\=")
                    + "="
                    + entry.getValue().replace("\\", "\\\\").replace(";", "\\;"));
        }
        return String.join(";", parts);
    }

    private Map<String, String> decodeEnv(String raw) {
        Map<String, String> env = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return env;
        }
        for (String part : raw.split("(?<!\\\\);")) {
            String[] kv = part.split("(?<!\\\\)=", 2);
            if (kv.length == 2) {
                env.put(kv[0].replace("\\=", "=").replace("\\\\", "\\"),
                        kv[1].replace("\\;", ";").replace("\\\\", "\\"));
            }
        }
        return env;
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
