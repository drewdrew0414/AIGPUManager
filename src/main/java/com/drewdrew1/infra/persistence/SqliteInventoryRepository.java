package com.drewdrew1.infra.persistence;

import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.HealthState;
import com.drewdrew1.core.model.InterconnectType;
import com.drewdrew1.core.model.NodeInventory;
import com.drewdrew1.core.repository.InventoryRepository;

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

public class SqliteInventoryRepository implements InventoryRepository {
    private final Path dbPath;

    public SqliteInventoryRepository(Path dbPath) {
        this.dbPath = dbPath;
    }

    @Override
    public void initialize() {
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
                    CREATE TABLE IF NOT EXISTS nodes (
                      hostname TEXT PRIMARY KEY,
                      os_name TEXT NOT NULL,
                      os_arch TEXT NOT NULL,
                      cpu_cores INTEGER NOT NULL,
                      memory_total_mb INTEGER NOT NULL,
                      last_scanned_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS gpus (
                      node_hostname TEXT NOT NULL,
                      vendor TEXT NOT NULL,
                      device_id TEXT,
                      model TEXT,
                      uuid TEXT,
                      pci_bus_id TEXT,
                      driver_version TEXT,
                      vram_total_mb INTEGER,
                      vram_free_mb INTEGER,
                      utilization_gpu REAL,
                      utilization_memory REAL,
                      temperature_c REAL,
                      power_usage_w REAL,
                      power_limit_w REAL,
                      ecc_enabled INTEGER,
                      interconnect_type TEXT NOT NULL,
                      health_state TEXT NOT NULL,
                      supports_mig INTEGER NOT NULL,
                      supports_partitioning INTEGER NOT NULL,
                      supports_compute INTEGER NOT NULL,
                      supports_container_runtime INTEGER NOT NULL,
                      last_scanned_at TEXT NOT NULL
                    )
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite schema", e);
        }
    }

    @Override
    public void saveNode(NodeInventory nodeInventory) {
        initialize();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO nodes(hostname, os_name, os_arch, cpu_cores, memory_total_mb, last_scanned_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                     ON CONFLICT(hostname) DO UPDATE SET
                       os_name = excluded.os_name,
                       os_arch = excluded.os_arch,
                       cpu_cores = excluded.cpu_cores,
                       memory_total_mb = excluded.memory_total_mb,
                       last_scanned_at = excluded.last_scanned_at
                     """)) {
            statement.setString(1, nodeInventory.hostname());
            statement.setString(2, nodeInventory.osName());
            statement.setString(3, nodeInventory.osArch());
            statement.setInt(4, nodeInventory.cpuCores());
            statement.setLong(5, nodeInventory.memoryTotalMb());
            statement.setString(6, nodeInventory.lastScannedAt().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert node inventory", e);
        }
    }

    @Override
    public void replaceNodeGpus(String hostname, List<GpuDevice> gpus) {
        initialize();
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteStatement =
                         connection.prepareStatement("DELETE FROM gpus WHERE node_hostname = ?");
                 PreparedStatement insertStatement = connection.prepareStatement("""
                         INSERT INTO gpus(
                           node_hostname, vendor, device_id, model, uuid, pci_bus_id, driver_version,
                           vram_total_mb, vram_free_mb, utilization_gpu, utilization_memory,
                           temperature_c, power_usage_w, power_limit_w, ecc_enabled,
                           interconnect_type, health_state, supports_mig, supports_partitioning,
                           supports_compute, supports_container_runtime, last_scanned_at
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """)) {
                deleteStatement.setString(1, hostname);
                deleteStatement.executeUpdate();

                for (GpuDevice gpu : gpus) {
                    insertStatement.setString(1, gpu.nodeHostname());
                    insertStatement.setString(2, gpu.vendor().name());
                    insertStatement.setString(3, gpu.deviceId());
                    insertStatement.setString(4, gpu.model());
                    insertStatement.setString(5, gpu.uuid());
                    insertStatement.setString(6, gpu.pciBusId());
                    insertStatement.setString(7, gpu.driverVersion());
                    setLong(insertStatement, 8, gpu.vramTotalMb());
                    setLong(insertStatement, 9, gpu.vramFreeMb());
                    setDouble(insertStatement, 10, gpu.utilizationGpu());
                    setDouble(insertStatement, 11, gpu.utilizationMemory());
                    setDouble(insertStatement, 12, gpu.temperatureC());
                    setDouble(insertStatement, 13, gpu.powerUsageW());
                    setDouble(insertStatement, 14, gpu.powerLimitW());
                    setBoolean(insertStatement, 15, gpu.eccEnabled());
                    insertStatement.setString(16, gpu.interconnectType().name());
                    insertStatement.setString(17, gpu.healthState().name());
                    insertStatement.setInt(18, gpu.supportsMig() ? 1 : 0);
                    insertStatement.setInt(19, gpu.supportsPartitioning() ? 1 : 0);
                    insertStatement.setInt(20, gpu.supportsCompute() ? 1 : 0);
                    insertStatement.setInt(21, gpu.supportsContainerRuntime() ? 1 : 0);
                    insertStatement.setString(22, gpu.lastScannedAt().toString());
                    insertStatement.addBatch();
                }
                insertStatement.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to replace node GPUs", e);
        }
    }

    @Override
    public List<NodeInventory> listNodes() {
        initialize();
        List<NodeInventory> nodes = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT hostname, os_name, os_arch, cpu_cores, memory_total_mb, last_scanned_at
                     FROM nodes
                     ORDER BY hostname
                     """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                nodes.add(new NodeInventory(
                        rs.getString("hostname"),
                        rs.getString("os_name"),
                        rs.getString("os_arch"),
                        rs.getInt("cpu_cores"),
                        rs.getLong("memory_total_mb"),
                        Instant.parse(rs.getString("last_scanned_at"))
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list nodes", e);
        }
        return nodes;
    }

    @Override
    public List<GpuDevice> listGpus() {
        initialize();
        List<GpuDevice> gpus = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT node_hostname, vendor, device_id, model, uuid, pci_bus_id, driver_version,
                            vram_total_mb, vram_free_mb, utilization_gpu, utilization_memory,
                            temperature_c, power_usage_w, power_limit_w, ecc_enabled,
                            interconnect_type, health_state, supports_mig, supports_partitioning,
                            supports_compute, supports_container_runtime, last_scanned_at
                     FROM gpus
                     ORDER BY node_hostname, vendor, model, device_id
                     """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                gpus.add(new GpuDevice(
                        rs.getString("node_hostname"),
                        GpuVendor.valueOf(rs.getString("vendor")),
                        rs.getString("device_id"),
                        rs.getString("model"),
                        rs.getString("uuid"),
                        rs.getString("pci_bus_id"),
                        rs.getString("driver_version"),
                        getLong(rs, "vram_total_mb"),
                        getLong(rs, "vram_free_mb"),
                        getDouble(rs, "utilization_gpu"),
                        getDouble(rs, "utilization_memory"),
                        getDouble(rs, "temperature_c"),
                        getDouble(rs, "power_usage_w"),
                        getDouble(rs, "power_limit_w"),
                        getBoolean(rs, "ecc_enabled"),
                        InterconnectType.valueOf(rs.getString("interconnect_type")),
                        HealthState.valueOf(rs.getString("health_state")),
                        rs.getInt("supports_mig") == 1,
                        rs.getInt("supports_partitioning") == 1,
                        rs.getInt("supports_compute") == 1,
                        rs.getInt("supports_container_runtime") == 1,
                        Instant.parse(rs.getString("last_scanned_at"))
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list GPUs", e);
        }
        return gpus;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static void setBoolean(PreparedStatement statement, int index, Boolean value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value ? 1 : 0);
        }
    }

    private static Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double getDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean getBoolean(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value == 1;
    }
}
