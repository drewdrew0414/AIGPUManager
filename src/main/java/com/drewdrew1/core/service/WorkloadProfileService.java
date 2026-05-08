package com.drewdrew1.core.service;

import com.drewdrew1.core.model.WorkloadEstimate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Provides conservative VRAM estimates for common AI workload shapes. */
public class WorkloadProfileService {
    public WorkloadEstimate estimate(String model, long parametersBillions, String precision, int contextLength, int batchSize) {
        if (parametersBillions <= 0) {
            throw new IllegalArgumentException("parameters must be > 0");
        }
        if (contextLength <= 0) {
            throw new IllegalArgumentException("context length must be > 0");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batch size must be > 0");
        }
        double bytesPerParam = bytesPerParam(precision);
        long weightMb = Math.round(parametersBillions * 1_000_000_000d * bytesPerParam / (1024d * 1024d));
        long hiddenSize = estimateHiddenSize(parametersBillions);
        long layerCount = estimateLayerCount(parametersBillions);
        long kvMb = Math.round((double) batchSize * contextLength * hiddenSize * layerCount * 2d * bytesPerParam / (1024d * 1024d));
        long overheadMb = Math.max(2048L, Math.round((weightMb + kvMb) * 0.15));
        long recommended = weightMb + kvMb + overheadMb;
        List<String> notes = new ArrayList<>();
        notes.add("heuristic_estimate");
        notes.add("verify_with_runtime_profiler_before_production");
        if (precision.toLowerCase(Locale.ROOT).contains("4")) {
            notes.add("4bit_routing_requires_kernel_and_runtime_support");
        }
        return new WorkloadEstimate(model, parametersBillions, precision, contextLength, batchSize, weightMb, kvMb, overheadMb, recommended, notes);
    }

    private double bytesPerParam(String precision) {
        String normalized = precision == null ? "fp16" : precision.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "fp32", "float32" -> 4.0;
            case "int8", "8bit" -> 1.0;
            case "int4", "4bit", "nf4" -> 0.55;
            default -> 2.0;
        };
    }

    private long estimateHiddenSize(long parametersBillions) {
        if (parametersBillions <= 8) {
            return 4096;
        }
        if (parametersBillions <= 14) {
            return 5120;
        }
        if (parametersBillions <= 35) {
            return 8192;
        }
        if (parametersBillions <= 80) {
            return 8192;
        }
        return 12288;
    }

    private long estimateLayerCount(long parametersBillions) {
        if (parametersBillions <= 8) {
            return 32;
        }
        if (parametersBillions <= 14) {
            return 40;
        }
        if (parametersBillions <= 35) {
            return 60;
        }
        if (parametersBillions <= 80) {
            return 80;
        }
        return 120;
    }
}
