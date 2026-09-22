package com.github.xingjianluo.ephemeralenvcontrolplanejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 本地控制平面可配置项（规模/耗时上限、TTL、扫描开关、演示数据）。 */
@Component
@ConfigurationProperties(prefix = "ecp")
public class ControlPlaneProperties {

    private boolean bootstrapDemo = true;
    private int listMaxLimit = 100;
    private Lease lease = new Lease();
    private Scan scan = new Scan();

    public static class Lease {
        private boolean scanEnabled = false;
        private long scanIntervalMs = 1000;
        private long defaultTtlSeconds = 300;
        private long maxTtlSeconds = 3600;

        public boolean isScanEnabled() { return scanEnabled; }
        public void setScanEnabled(boolean scanEnabled) { this.scanEnabled = scanEnabled; }
        public long getScanIntervalMs() { return scanIntervalMs; }
        public void setScanIntervalMs(long scanIntervalMs) { this.scanIntervalMs = scanIntervalMs; }
        public long getDefaultTtlSeconds() { return defaultTtlSeconds; }
        public void setDefaultTtlSeconds(long defaultTtlSeconds) { this.defaultTtlSeconds = defaultTtlSeconds; }
        public long getMaxTtlSeconds() { return maxTtlSeconds; }
        public void setMaxTtlSeconds(long maxTtlSeconds) { this.maxTtlSeconds = maxTtlSeconds; }
    }

    public static class Scan {
        private int maxBatch = 50;
        private long maxMillis = 200;

        public int getMaxBatch() { return maxBatch; }
        public void setMaxBatch(int maxBatch) { this.maxBatch = maxBatch; }
        public long getMaxMillis() { return maxMillis; }
        public void setMaxMillis(long maxMillis) { this.maxMillis = maxMillis; }
    }

    public boolean isBootstrapDemo() { return bootstrapDemo; }
    public void setBootstrapDemo(boolean bootstrapDemo) { this.bootstrapDemo = bootstrapDemo; }
    public int getListMaxLimit() { return listMaxLimit; }
    public void setListMaxLimit(int listMaxLimit) { this.listMaxLimit = listMaxLimit; }
    public Lease getLease() { return lease; }
    public void setLease(Lease lease) { this.lease = lease; }
    public Scan getScan() { return scan; }
    public void setScan(Scan scan) { this.scan = scan; }
}
