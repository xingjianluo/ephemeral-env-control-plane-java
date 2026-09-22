package com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceSpec;

import java.util.List;
import java.util.Map;

/** 环境申请请求。 */
public class ApplyRequest {
    private String tenantId;
    private String name;
    private String owner;
    private String templateVersion;
    private Long ttlSeconds;
    private List<ResourceSpec> resources;
    private Map<String, Object> configOverrides;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(String templateVersion) { this.templateVersion = templateVersion; }
    public Long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    public List<ResourceSpec> getResources() { return resources; }
    public void setResources(List<ResourceSpec> resources) { this.resources = resources; }
    public Map<String, Object> getConfigOverrides() { return configOverrides; }
    public void setConfigOverrides(Map<String, Object> configOverrides) { this.configOverrides = configOverrides; }
}
