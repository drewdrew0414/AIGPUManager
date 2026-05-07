package com.drewdrew1.core.detector;

import java.time.Instant;

/** Reads GPU inventory for a single vendor from the current execution target. */
public interface GpuDetector {
    DetectionResult detect(String hostname, Instant scannedAt);
}
