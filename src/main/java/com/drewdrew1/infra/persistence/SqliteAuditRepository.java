package com.drewdrew1.infra.persistence;

import com.drewdrew1.core.model.AuditEvent;
import com.drewdrew1.core.model.AuditQuery;
import com.drewdrew1.core.repository.AuditRepository;

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
import java.util.List;

/** Stores immutable audit events in a local SQLite database. */
public class SqliteAuditRepository implements AuditRepository {
    private final Path dbPath;
    private boolean initialized;

    public SqliteAuditRepository(Path dbPath) {
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
                    CREATE TABLE IF NOT EXISTS audit_events (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      created_at TEXT NOT NULL,
                      event_type TEXT NOT NULL,
                      actor TEXT NOT NULL,
                      target TEXT NOT NULL,
                      details TEXT NOT NULL
                    )
                    """);
            initialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize audit schema", e);
        }
    }

    @Override
    public void append(String eventType, String actor, String target, String details) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO audit_events(created_at, event_type, actor, target, details)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, eventType);
            statement.setString(3, actor);
            statement.setString(4, target);
            statement.setString(5, details);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append audit event", e);
        }
    }

    @Override
    public List<AuditEvent> list() {
        initialize();
        return load(null, null, null);
    }

    @Override
    public List<AuditEvent> traceByTarget(String target) {
        initialize();
        return load("WHERE target = ?", target, null);
    }

    @Override
    public List<AuditEvent> listBetween(Instant from, Instant to) {
        initialize();
        return load("WHERE created_at >= ? AND created_at <= ?", from.toString(), to.toString());
    }

    @Override
    public List<AuditEvent> query(AuditQuery query) {
        initialize();
        StringBuilder sql = new StringBuilder("""
                SELECT id, created_at, event_type, actor, target, details
                FROM audit_events
                WHERE 1=1
                """);
        List<String> parameters = new ArrayList<>();
        if (query.eventType() != null) {
            sql.append(" AND lower(event_type) = lower(?)");
            parameters.add(query.eventType());
        }
        if (query.actor() != null) {
            sql.append(" AND lower(actor) = lower(?)");
            parameters.add(query.actor());
        }
        if (query.target() != null) {
            sql.append(" AND lower(target) = lower(?)");
            parameters.add(query.target());
        }
        if (query.contains() != null) {
            sql.append(" AND lower(details) LIKE lower(?)");
            parameters.add("%" + query.contains() + "%");
        }
        if (query.from() != null) {
            sql.append(" AND created_at >= ?");
            parameters.add(query.from().toString());
        }
        if (query.to() != null) {
            sql.append(" AND created_at <= ?");
            parameters.add(query.to().toString());
        }
        sql.append(" ORDER BY created_at ").append(query.ascending() ? "ASC" : "DESC");
        if (query.limit() != null) {
            sql.append(" LIMIT ").append(query.limit());
        }

        List<AuditEvent> events = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setString(i + 1, parameters.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    events.add(new AuditEvent(
                            rs.getLong("id"),
                            Instant.parse(rs.getString("created_at")),
                            rs.getString("event_type"),
                            rs.getString("actor"),
                            rs.getString("target"),
                            rs.getString("details")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query audit events", e);
        }
        return events;
    }

    private List<AuditEvent> load(String whereClause, String value1, String value2) {
        String sql = """
                SELECT id, created_at, event_type, actor, target, details
                FROM audit_events
                %s
                ORDER BY id DESC
                """.formatted(whereClause == null ? "" : whereClause);
        List<AuditEvent> events = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (value1 != null) {
                statement.setString(1, value1);
            }
            if (value2 != null) {
                statement.setString(2, value2);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    events.add(new AuditEvent(
                            rs.getLong("id"),
                            Instant.parse(rs.getString("created_at")),
                            rs.getString("event_type"),
                            rs.getString("actor"),
                            rs.getString("target"),
                            rs.getString("details")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load audit events", e);
        }
        return events;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }
}
