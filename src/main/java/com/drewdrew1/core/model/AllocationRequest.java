package com.drewdrew1.core.model;

/** Captures user-facing request constraints for GPU allocation. */
public record AllocationRequest(
        String owner,
        String tenant,
        int gpuCount,
        String model,
        Long minVramMb,
        boolean exclusiveNode,
        int hours,
        int priority,
        boolean preemptible,
        AllocationAffinity affinity,
        String labelSelector
) {
}
