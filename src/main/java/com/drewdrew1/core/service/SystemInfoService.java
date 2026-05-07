package com.drewdrew1.core.service;

import com.drewdrew1.core.model.NodeInventory;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Instant;

/** Collects node-level system information from the local machine. */
public class SystemInfoService implements NodeInfoProvider {
    public NodeInventory localNodeInventory() {
        String hostname = resolveHostname();
        String osName = System.getProperty("os.name", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long memoryTotalMb = resolveTotalMemoryMb();
        return new NodeInventory(hostname, osName, osArch, cpuCores, memoryTotalMb, Instant.now());
    }

    @Override
    public NodeInventory collectNodeInventory() {
        return localNodeInventory();
    }

    private long resolveTotalMemoryMb() {
        try {
            OperatingSystemMXBean bean =
                    (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return bean.getTotalMemorySize() / (1024 * 1024);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "unknown-host";
        }
    }
}
