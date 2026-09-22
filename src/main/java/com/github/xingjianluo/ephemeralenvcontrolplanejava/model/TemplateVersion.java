package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

import java.util.Map;

/**
 * 配置模板的一个版本：严格字段集合 + 字段默认值 + 敏感字段名。
 * 新版本只能新增字段（带稳定默认值），不得改变或删除旧字段语义；
 * 敏感字段值单独存放，不允许出现在普通配置视图、审计与日志中。
 */
public record TemplateVersion(String version, String secretField, Map<String, Object> defaults) {
}
