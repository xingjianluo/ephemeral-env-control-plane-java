package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/**
 * 租户配额：上限与已用量。默认上限 0（即未配置时拒绝申请），
 * 由 DemoDataInitializer 或管理接口显式设置。
 */
public class Quota {

    private int maxEnvs;
    private int maxCpu;
    private int maxMemoryMb;
    private int usedEnvs;
    private int usedCpu;
    private int usedMemoryMb;

    public Quota() {
    }

    /** 检查并原子占用；返回拒绝原因，返回 null 表示成功。 */
    public String tryReserve(int cpu, int memoryMb) {
        if (usedEnvs + 1 > maxEnvs) {
            return "environment count quota exceeded: used=" + usedEnvs + ", max=" + maxEnvs;
        }
        if (usedCpu + cpu > maxCpu) {
            return "cpu quota exceeded: requested=" + cpu + ", used=" + usedCpu + ", max=" + maxCpu;
        }
        if (usedMemoryMb + memoryMb > maxMemoryMb) {
            return "memory quota exceeded: requested=" + memoryMb + ", used=" + usedMemoryMb
                    + ", max=" + maxMemoryMb;
        }
        usedEnvs++;
        usedCpu += cpu;
        usedMemoryMb += memoryMb;
        return null;
    }

    /** 归还占用。 */
    public void release(int cpu, int memoryMb) {
        usedEnvs = Math.max(0, usedEnvs - 1);
        usedCpu = Math.max(0, usedCpu - cpu);
        usedMemoryMb = Math.max(0, usedMemoryMb - memoryMb);
    }

    public int getMaxEnvs() { return maxEnvs; }
    public int getMaxCpu() { return maxCpu; }
    public int getMaxMemoryMb() { return maxMemoryMb; }
    public int getUsedEnvs() { return usedEnvs; }
    public int getUsedCpu() { return usedCpu; }
    public int getUsedMemoryMb() { return usedMemoryMb; }

    public void setMaxEnvs(int maxEnvs) { this.maxEnvs = maxEnvs; }
    public void setMaxCpu(int maxCpu) { this.maxCpu = maxCpu; }
    public void setMaxMemoryMb(int maxMemoryMb) { this.maxMemoryMb = maxMemoryMb; }
    public void setUsedEnvs(int usedEnvs) { this.usedEnvs = usedEnvs; }
    public void setUsedCpu(int usedCpu) { this.usedCpu = usedCpu; }
    public void setUsedMemoryMb(int usedMemoryMb) { this.usedMemoryMb = usedMemoryMb; }
}
