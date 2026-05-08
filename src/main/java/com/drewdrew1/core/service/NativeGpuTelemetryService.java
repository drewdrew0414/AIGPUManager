package com.drewdrew1.core.service;

import com.drewdrew1.core.model.GpuVendor;
import com.drewdrew1.core.model.NativeGpuMetric;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.List;

/** Collects optional native GPU metrics through NVML and Level Zero when those libraries exist. */
public class NativeGpuTelemetryService {
    public List<NativeGpuMetric> collect() {
        List<NativeGpuMetric> metrics = new ArrayList<>();
        metrics.addAll(collectNvml());
        metrics.addAll(collectLevelZero());
        return metrics;
    }

    public List<NativeGpuMetric> collectNvml() {
        try {
            Nvml nvml = Native.load("nvml", Nvml.class);
            int init = nvml.nvmlInit_v2();
            if (init != 0) {
                return List.of(unavailable(GpuVendor.NVIDIA, "NVML init failed: code=" + init));
            }
            try {
                IntByReference countRef = new IntByReference();
                int countResult = nvml.nvmlDeviceGetCount_v2(countRef);
                if (countResult != 0) {
                    return List.of(unavailable(GpuVendor.NVIDIA, "NVML device count failed: code=" + countResult));
                }
                List<NativeGpuMetric> metrics = new ArrayList<>();
                for (int i = 0; i < countRef.getValue(); i++) {
                    PointerByReference handleRef = new PointerByReference();
                    int handleResult = nvml.nvmlDeviceGetHandleByIndex_v2(i, handleRef);
                    if (handleResult != 0) {
                        metrics.add(unavailable(GpuVendor.NVIDIA, "NVML handle failed for index " + i + ": code=" + handleResult));
                        continue;
                    }
                    Pointer handle = handleRef.getValue();
                    NvmlMemory memory = new NvmlMemory();
                    NvmlUtilization utilization = new NvmlUtilization();
                    int memoryResult = nvml.nvmlDeviceGetMemoryInfo(handle, memory);
                    int utilResult = nvml.nvmlDeviceGetUtilizationRates(handle, utilization);
                    metrics.add(new NativeGpuMetric(
                            GpuVendor.NVIDIA,
                            i,
                            "NVIDIA-" + i,
                            memoryResult == 0 ? bytesToMb(memory.total) : null,
                            memoryResult == 0 ? bytesToMb(memory.used) : null,
                            utilResult == 0 ? (double) utilization.gpu : null,
                            "nvml",
                            true,
                            memoryResult == 0 && utilResult == 0 ? null : "partial native metric: memory=" + memoryResult + ", util=" + utilResult
                    ));
                }
                return metrics;
            } finally {
                nvml.nvmlShutdown();
            }
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | RuntimeException e) {
            return List.of(unavailable(GpuVendor.NVIDIA, "NVML unavailable: " + e.getMessage()));
        }
    }

    public List<NativeGpuMetric> collectLevelZero() {
        try {
            LevelZero levelZero = Native.load("ze_loader", LevelZero.class);
            int init = levelZero.zeInit(0);
            if (init != 0) {
                return List.of(unavailable(GpuVendor.INTEL, "Level Zero init failed: code=" + init));
            }
            IntByReference driverCountRef = new IntByReference(0);
            int countResult = levelZero.zeDriverGet(driverCountRef, null);
            if (countResult != 0 || driverCountRef.getValue() == 0) {
                return List.of(unavailable(GpuVendor.INTEL, "Level Zero driver count unavailable: code=" + countResult));
            }
            Pointer[] drivers = new Pointer[driverCountRef.getValue()];
            int driverResult = levelZero.zeDriverGet(driverCountRef, drivers);
            if (driverResult != 0) {
                return List.of(unavailable(GpuVendor.INTEL, "Level Zero driver get failed: code=" + driverResult));
            }
            List<NativeGpuMetric> metrics = new ArrayList<>();
            int index = 0;
            for (Pointer driver : drivers) {
                IntByReference deviceCountRef = new IntByReference(0);
                int deviceCountResult = levelZero.zeDeviceGet(driver, deviceCountRef, null);
                if (deviceCountResult != 0 || deviceCountRef.getValue() == 0) {
                    continue;
                }
                Pointer[] devices = new Pointer[deviceCountRef.getValue()];
                int deviceResult = levelZero.zeDeviceGet(driver, deviceCountRef, devices);
                if (deviceResult != 0) {
                    continue;
                }
                for (Pointer ignored : devices) {
                    metrics.add(new NativeGpuMetric(
                            GpuVendor.INTEL,
                            index,
                            "Intel-LevelZero-" + index,
                            null,
                            null,
                            null,
                            "level-zero",
                            true,
                            "Level Zero device detected; utilization still comes from xpu-smi/Windows counters"
                    ));
                    index++;
                }
            }
            if (metrics.isEmpty()) {
                return List.of(unavailable(GpuVendor.INTEL, "Level Zero found no devices"));
            }
            return metrics;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | RuntimeException e) {
            return List.of(unavailable(GpuVendor.INTEL, "Level Zero unavailable: " + e.getMessage()));
        }
    }

    private NativeGpuMetric unavailable(GpuVendor vendor, String warning) {
        return new NativeGpuMetric(vendor, -1, vendor.name(), null, null, null, "native", false, warning);
    }

    private Long bytesToMb(long bytes) {
        return Math.round(bytes / 1024.0 / 1024.0);
    }

    /** Minimal NVML JNA surface used for polling-free native metric reads. */
    public interface Nvml extends Library {
        int nvmlInit_v2();
        int nvmlShutdown();
        int nvmlDeviceGetCount_v2(IntByReference deviceCount);
        int nvmlDeviceGetHandleByIndex_v2(int index, PointerByReference device);
        int nvmlDeviceGetMemoryInfo(Pointer device, NvmlMemory memory);
        int nvmlDeviceGetUtilizationRates(Pointer device, NvmlUtilization utilization);
    }

    /** Minimal Level Zero JNA surface used to verify native Intel device access. */
    public interface LevelZero extends Library {
        int zeInit(int flags);
        int zeDriverGet(IntByReference count, Pointer[] drivers);
        int zeDeviceGet(Pointer driver, IntByReference count, Pointer[] devices);
    }

    /** Native NVML memory structure. */
    @Structure.FieldOrder({"total", "free", "used"})
    public static class NvmlMemory extends Structure {
        public long total;
        public long free;
        public long used;
    }

    /** Native NVML utilization structure. */
    @Structure.FieldOrder({"gpu", "memory"})
    public static class NvmlUtilization extends Structure {
        public int gpu;
        public int memory;
    }
}
