package com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto;

/** 续期请求。 */
public class RenewRequest {
    private String tenantId;
    private Long ttlSeconds;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
}
