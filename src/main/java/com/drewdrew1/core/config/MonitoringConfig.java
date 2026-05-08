package com.drewdrew1.core.config;

/** Holds polling, cache, and health threshold defaults for runtime monitoring. */
public class MonitoringConfig {
    private int scanMinIntervalSec = 5;
    private double quarantineScoreThreshold = 40.0;
    private double thermalWarnC = 75.0;
    private double thermalCriticalC = 85.0;

    public int getScanMinIntervalSec() {
        return scanMinIntervalSec;
    }

    public void setScanMinIntervalSec(int scanMinIntervalSec) {
        this.scanMinIntervalSec = scanMinIntervalSec;
    }

    public double getQuarantineScoreThreshold() {
        return quarantineScoreThreshold;
    }

    public void setQuarantineScoreThreshold(double quarantineScoreThreshold) {
        this.quarantineScoreThreshold = quarantineScoreThreshold;
    }

    public double getThermalWarnC() {
        return thermalWarnC;
    }

    public void setThermalWarnC(double thermalWarnC) {
        this.thermalWarnC = thermalWarnC;
    }

    public double getThermalCriticalC() {
        return thermalCriticalC;
    }

    public void setThermalCriticalC(double thermalCriticalC) {
        this.thermalCriticalC = thermalCriticalC;
    }
}
