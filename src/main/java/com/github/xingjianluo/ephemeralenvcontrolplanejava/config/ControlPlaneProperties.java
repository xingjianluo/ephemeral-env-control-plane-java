package com.github.xingjianluo.ephemeralenvcontrolplanejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded-scan and lease knobs (application.properties). */
@ConfigurationProperties(prefix = "cp")
public class ControlPlaneProperties {

    /** Maximum environments handled by a single expiry scan page. */
    private int scanPageSize = 100;

    /** Maximum rows returned by a bulk query. */
    private int bulkQueryLimit = 200;

    /** Hard cap (ms) for a single expiry sweep; remainder is deferred. */
    private long scanBudgetMillis = 1000;

    /** Interval (ms) of the expiry reaper; 0 disables scheduling. */
    private long reaperIntervalMillis = 5000;

    /** Maximum number of backend resources a single reconcile pass touches. */
    private int reconcileBatchSize = 100;

    public int getScanPageSize() { return scanPageSize; }
    public void setScanPageSize(int scanPageSize) { this.scanPageSize = scanPageSize; }
    public int getBulkQueryLimit() { return bulkQueryLimit; }
    public void setBulkQueryLimit(int bulkQueryLimit) { this.bulkQueryLimit = bulkQueryLimit; }
    public long getScanBudgetMillis() { return scanBudgetMillis; }
    public void setScanBudgetMillis(long scanBudgetMillis) { this.scanBudgetMillis = scanBudgetMillis; }
    public long getReaperIntervalMillis() { return reaperIntervalMillis; }
    public void setReaperIntervalMillis(long reaperIntervalMillis) { this.reaperIntervalMillis = reaperIntervalMillis; }
    public int getReconcileBatchSize() { return reconcileBatchSize; }
    public void setReconcileBatchSize(int reconcileBatchSize) { this.reconcileBatchSize = reconcileBatchSize; }
}
