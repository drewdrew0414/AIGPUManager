package com.drewdrew1.core.service;

import com.drewdrew1.core.model.AllocationRecord;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuHealthScore;
import com.drewdrew1.core.repository.AllocationRepository;
import com.drewdrew1.core.repository.InventoryRepository;

import java.util.List;

/** Renders current gpum inventory and allocation state in Prometheus text format. */
public class PrometheusExportService {
    private final InventoryRepository inventoryRepository;
    private final AllocationRepository allocationRepository;
    private final HealthScoringService healthScoringService;

    public PrometheusExportService(
            InventoryRepository inventoryRepository,
            AllocationRepository allocationRepository,
            HealthScoringService healthScoringService
    ) {
        this.inventoryRepository = inventoryRepository;
        this.allocationRepository = allocationRepository;
        this.healthScoringService = healthScoringService;
    }

    public String render() {
        inventoryRepository.initialize();
        allocationRepository.initialize();
        StringBuilder sb = new StringBuilder();
        List<GpuHealthScore> scores = healthScoringService.scoreAll();
        sb.append("# HELP gpum_gpu_utilization_percent Last scanned GPU utilization percent\n");
        sb.append("# TYPE gpum_gpu_utilization_percent gauge\n");
        for (GpuDevice gpu : inventoryRepository.listGpus()) {
            labels(sb, "gpum_gpu_utilization_percent", gpu).append(' ')
                    .append(number(gpu.utilizationGpu())).append('\n');
            labels(sb, "gpum_gpu_memory_free_mb", gpu).append(' ')
                    .append(gpu.vramFreeMb() == null ? 0 : gpu.vramFreeMb()).append('\n');
            labels(sb, "gpum_gpu_temperature_celsius", gpu).append(' ')
                    .append(number(gpu.temperatureC())).append('\n');
            labels(sb, "gpum_gpu_power_watts", gpu).append(' ')
                    .append(number(gpu.powerUsageW())).append('\n');
        }
        sb.append("# HELP gpum_gpu_health_score Computed GPU health score from 0 to 100\n");
        sb.append("# TYPE gpum_gpu_health_score gauge\n");
        for (GpuHealthScore score : scores) {
            sb.append("gpum_gpu_health_score")
                    .append("{node=\"").append(escape(score.nodeHostname()))
                    .append("\",device_id=\"").append(escape(score.deviceId()))
                    .append("\",vendor=\"").append(score.vendor().name())
                    .append("\",model=\"").append(escape(score.model()))
                    .append("\"} ").append(String.format(java.util.Locale.ROOT, "%.2f", score.score())).append('\n');
        }
        sb.append("# HELP gpum_allocations_active Active allocation count\n");
        sb.append("# TYPE gpum_allocations_active gauge\n");
        sb.append("gpum_allocations_active ").append(allocationRepository.listActive().size()).append('\n');
        sb.append("# HELP gpum_allocation_gpus_active Active allocated GPU count\n");
        sb.append("# TYPE gpum_allocation_gpus_active gauge\n");
        int activeGpus = 0;
        for (AllocationRecord record : allocationRepository.listActive()) {
            activeGpus += record.devices().size();
        }
        sb.append("gpum_allocation_gpus_active ").append(activeGpus).append('\n');
        return sb.toString();
    }

    private StringBuilder labels(StringBuilder sb, String metric, GpuDevice gpu) {
        return sb.append(metric)
                .append("{node=\"").append(escape(gpu.nodeHostname()))
                .append("\",device_id=\"").append(escape(gpu.deviceId()))
                .append("\",vendor=\"").append(gpu.vendor().name())
                .append("\",model=\"").append(escape(gpu.model()))
                .append("\"}");
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String number(Double value) {
        return value == null ? "0" : String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
