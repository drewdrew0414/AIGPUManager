package com.drewdrew1.core.model;

import java.util.List;

/** Represents a rough VRAM estimate for one AI workload shape. */
public record WorkloadEstimate(
        String model,
        long parametersBillions,
        String precision,
        int contextLength,
        int batchSize,
        long weightMemoryMb,
        long kvCacheMb,
        long runtimeOverheadMb,
        long recommendedVramMb,
        List<String> notes
) {
}
