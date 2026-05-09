package com.drewdrew1.infra.persistence;

import com.drewdrew1.core.model.OpsRecord;
import com.drewdrew1.core.repository.OpsRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores cross-cutting operational records in SQLite. */
public class SqliteOpsRepository implements OpsRepository {
    private final Path dbPath;
    private boolean initialized;

    public SqliteOpsRepository(Path dbPath) {
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
            throw new IllegalStateException("Failed to create ops database directory", e);
        }
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ops_records (
                      id TEXT PRIMARY KEY,
                      domain TEXT NOT NULL,
                      type TEXT NOT NULL,
                      name TEXT NOT NULL,
                      owner TEXT NOT NULL,
                      target TEXT,
                      status TEXT NOT NULL,
                      metadata TEXT,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ops_domain_type ON ops_records(domain, type)");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            initialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize ops schema", e);
        }
    }

    @Override
    public OpsRecord upsert(OpsRecord record) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ops_records(id, domain, type, name, owner, target, status, metadata, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET
                       domain = excluded.domain,
                       type = excluded.type,
                       name = excluded.name,
                       owner = excluded.owner,
                       target = excluded.target,
                       status = excluded.status,
                       metadata = excluded.metadata,
                       updated_at = excluded.updated_at
                     """)) {
            statement.setString(1, record.id());
            statement.setString(2, record.domain());
            statement.setString(3, record.type());
            statement.setString(4, record.name());
            statement.setString(5, record.owner());
            statement.setString(6, record.target());
            statement.setString(7, record.status());
            statement.setString(8, encode(record.metadata()));
            statement.setString(9, record.createdAt().toString());
            statement.setString(10, record.updatedAt().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save ops record", e);
        }
        return find(record.id()).orElseThrow(() -> new IllegalStateException("Ops record was saved but not found: " + record.id()));
    }

    @Override
    public Optional<OpsRecord> find(String id) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM ops_records WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find ops record", e);
        }
    }

    @Override
    public List<OpsRecord> list(String domain, String type) {
        initialize();
        List<String> filters = new ArrayList<>();
        if (domain != null) {
            filters.add("domain = ?");
        }
        if (type != null) {
            filters.add("type = ?");
        }
        String sql = "SELECT * FROM ops_records"
                + (filters.isEmpty() ? "" : " WHERE " + String.join(" AND ", filters))
                + " ORDER BY updated_at DESC, id";
        List<OpsRecord> records = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (domain != null) {
                statement.setString(index++, domain);
            }
            if (type != null) {
                statement.setString(index, type);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    records.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list ops records", e);
        }
        return records;
    }

    @Override
    public int updateStatus(String id, String status) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE ops_records SET status = ?, updated_at = ? WHERE id = ?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, id);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update ops record status", e);
        }
    }

    @Override
    public int delete(String id) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM ops_records WHERE id = ?")) {
            statement.setString(1, id);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete ops record", e);
        }
    }

    private OpsRecord map(ResultSet rs) throws SQLException {
        return new OpsRecord(
                rs.getString("id"),
                rs.getString("domain"),
                rs.getString("type"),
                rs.getString("name"),
                rs.getString("owner"),
                rs.getString("target"),
                rs.getString("status"),
                decode(rs.getString("metadata")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private String encode(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            lines.add(escape(entry.getKey()) + "=" + escape(entry.getValue()));
        }
        return String.join("\n", lines);
    }

    private Map<String, String> decode(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String line : raw.split("\n")) {
            String[] parts = line.split("(?<!\\\\)=", 2);
            if (parts.length == 2) {
                values.put(unescape(parts[0]), unescape(parts[1]));
            }
        }
        return values;
    }

    private String escape(String value) {
        String safe = value == null ? "" : value;
        return safe.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=");
    }

    private String unescape(String value) {
        return value.replace("\\=", "=").replace("\\n", "\n").replace("\\\\", "\\");
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }
}
