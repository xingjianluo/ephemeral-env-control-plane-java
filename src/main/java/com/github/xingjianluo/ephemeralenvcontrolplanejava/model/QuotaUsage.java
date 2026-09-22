package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/** Point-in-time quota consumption of one tenant. */
public record QuotaUsage(String tenantId, int activeEnvs, int usedUnits,
                        int maxActiveEnvs, int maxResourceUnits) {

    public boolean envSlotAvailable() {
        return activeEnvs < maxActiveEnvs;
    }

    public boolean unitsAvailableFor(int units) {
        return usedUnits + units <= maxResourceUnits;
    }
}
