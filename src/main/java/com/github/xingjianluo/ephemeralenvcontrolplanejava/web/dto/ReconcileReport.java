package com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto;

/** 收敛报告（非敏感的统计信息）。 */
public class ReconcileReport {
    private int orphanResourcesRemoved;
    private int stuckEnvironmentsRecovered;
    private boolean truncatedByLimit;

    public int getOrphanResourcesRemoved() { return orphanResourcesRemoved; }
    public void setOrphanResourcesRemoved(int orphanResourcesRemoved) { this.orphanResourcesRemoved = orphanResourcesRemoved; }
    public int getStuckEnvironmentsRecovered() { return stuckEnvironmentsRecovered; }
    public void setStuckEnvironmentsRecovered(int stuckEnvironmentsRecovered) { this.stuckEnvironmentsRecovered = stuckEnvironmentsRecovered; }
    public boolean isTruncatedByLimit() { return truncatedByLimit; }
    public void setTruncatedByLimit(boolean truncatedByLimit) { this.truncatedByLimit = truncatedByLimit; }
}
