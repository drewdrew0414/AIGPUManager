package com.drewdrew1.infra.persistence;

import com.drewdrew1.core.model.LogEntry;
import com.drewdrew1.core.model.LogQuery;
import com.drewdrew1.core.repository.LogRepository;

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

/** Stores operational log entries in SQLite with filterable query support. */
public class SqliteLogRepository implements LogRepository {
    private final Path dbPath;
    private boolean initialized;

    public SqliteLogRepository(Path dbPath) {
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
                    CREATE TABLE IF NOT EXISTS log_entries (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      created_at TEXT NOT NULL,
                      level TEXT NOT NULL,
                      component TEXT NOT NULL,
                      category TEXT NOT NULL,
                      message TEXT NOT NULL,
                      context TEXT
                    )
                    """);
            initialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize log schema", e);
        }
    }

    @Override
    public void append(String level, String component, String category, String message, String context) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO log_entries(created_at, level, component, category, message, context)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, level);
            statement.setString(3, component);
            statement.setString(4, category);
            statement.setString(5, message);
            statement.setString(6, context);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append log entry", e);
        }
    }

    @Override
    public List<LogEntry> query(LogQuery query) {
        initialize();
        StringBuilder sql = new StringBuilder("""
                SELECT id, created_at, level, component, category, message, context
                FROM log_entries
                WHERE 1=1
                """);
        List<String> parameters = new ArrayList<>();
        if (query.level() != null) {
            sql.append(" AND lower(level) = lower(?)");
            parameters.add(query.level());
        }
        if (query.component() != null) {
            sql.append(" AND lower(component) = lower(?)");
            parameters.add(query.component());
        }
        if (query.category() != null) {
            sql.append(" AND lower(category) = lower(?)");
            parameters.add(query.category());
        }
        if (query.contains() != null) {
            sql.append(" AND (lower(message) LIKE lower(?) OR lower(coalesce(context, '')) LIKE lower(?))");
            String like = "%" + query.contains() + "%";
            parameters.add(like);
            parameters.add(like);
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

        List<LogEntry> entries = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setString(i + 1, parameters.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    entries.add(new LogEntry(
                            rs.getLong("id"),
                            Instant.parse(rs.getString("created_at")),
                            rs.getString("level"),
                            rs.getString("component"),
                            rs.getString("category"),
                            rs.getString("message"),
                            rs.getString("context")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query log entries", e);
        }
        return entries;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }
}
