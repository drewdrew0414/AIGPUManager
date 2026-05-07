package com.drewdrew1.core.detector;

import java.time.Instant;

public interface GpuDetector {
    DetectionResult detect(String hostname, Instant scannedAt);
}
