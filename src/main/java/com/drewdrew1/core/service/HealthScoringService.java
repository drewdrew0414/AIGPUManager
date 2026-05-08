package com.drewdrew1.core.service;

import com.drewdrew1.core.config.MonitoringConfig;
import com.drewdrew1.core.model.GpuDevice;
import com.drewdrew1.core.model.GpuHealthScore;
import com.drewdrew1.core.repository.InventoryRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Computes health scores and can quarantine unhealthy GPUs from scheduling. */
public class HealthScoringService {
    private final InventoryRepository inventoryRepository;
    private final MonitoringConfig monitoringConfig;

    public HealthScoringService(InventoryRepository inventoryRepository, MonitoringConfig monitoringConfig) {
        this.inventoryRepository = inventoryRepository;
        this.monitoringConfig = monitoringConfig;
    }

    public List<GpuHealthScore> scoreAll() {
        inventoryRepository.initialize();
        List<GpuHealthScore> scores = new ArrayList<>();
        for (GpuDevice gpu : inventoryRepository.listGpus()) {
            scores.add(score(gpu));
        }
        scores.sort(Comparator
                .comparing(GpuHealthScore::nodeHostname)
                .thenComparing(score -> score.deviceId() == null ? "" : score.deviceId()));
        return scores;
    }

    public GpuHealthScore score(GpuDevice gpu) {
        double score = 100.0;
        List<String> reasons = new ArrayList<>();
        if (gpu.healthState() != null && !"OK".equals(gpu.healthState().name())) {
            score -= 25.0;
            reasons.add("health_state=" + gpu.healthState().name());
        }
        if (Boolean.FALSE.equals(gpu.eccEnabled())) {
            score -= 12.0;
            reasons.add("ecc_disabled");
        }
        if (gpu.temperatureC() != null) {
            if (gpu.temperatureC() >= monitoringConfig.getThermalCriticalC()) {
                score -= 35.0;
                reasons.add("thermal_critical=" + gpu.temperatureC());
            } else if (gpu.temperatureC() >= monitoringConfig.getThermalWarnC()) {
                score -= 15.0;
                reasons.add("thermal_warn=" + gpu.temperatureC());
            }
        }
        if (gpu.vramTotalMb() != null && gpu.vramFreeMb() != null && gpu.vramTotalMb() > 0) {
            double usedRatio = (double) (gpu.vramTotalMb() - gpu.vramFreeMb()) / gpu.vramTotalMb();
            if (usedRatio >= 0.98) {
                score -= 20.0;
                reasons.add("vram_saturated");
            } else if (usedRatio >= 0.90) {
                score -= 8.0;
                reasons.add("vram_high");
            }
        }
        if (gpu.powerUsageW() != null && gpu.powerLimitW() != null && gpu.powerLimitW() > 0) {
            double powerRatio = gpu.powerUsageW() / gpu.powerLimitW();
            if (powerRatio >= 0.98) {
                score -= 8.0;
                reasons.add("near_power_limit");
            }
        }
        score = Math.max(0.0, Math.min(100.0, score));
        boolean degraded = score < 70.0;
        boolean quarantine = score < monitoringConfig.getQuarantineScoreThreshold();
        if (reasons.isEmpty()) {
            reasons.add("ok");
        }
        return new GpuHealthScore(
                gpu.nodeHostname(),
                gpu.deviceId(),
                gpu.uuid(),
                gpu.model(),
                gpu.vendor(),
                score,
                degraded,
                quarantine,
                List.copyOf(reasons)
        );
    }

    public int applyQuarantine(double threshold) {
        int changed = 0;
        for (GpuHealthScore score : scoreAll()) {
            if (score.score() < threshold) {
                inventoryRepository.putNodeAttribute(score.nodeHostname(), gpuQuarantineKey(score.deviceId()), "true");
                inventoryRepository.putNodeAttribute(score.nodeHostname(), gpuQuarantineKey(score.deviceId()) + ".reason", String.join(";", score.reasons()));
                changed++;
            }
        }
        return changed;
    }

    public static String gpuQuarantineKey(String deviceId) {
        return "gpu.quarantine." + (deviceId == null || deviceId.isBlank() ? "unknown" : deviceId);
    }
}
