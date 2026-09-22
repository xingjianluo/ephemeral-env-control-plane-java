package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

import java.util.Map;

/**
 * 渲染后的环境配置快照（在就绪时固化，模板后续升级不追溯影响）。
 * secretField 指向敏感字段名，其真实值单独保存在 Environment 中，审计/日志不可见。
 */
public record RenderedConfig(String templateVersion, Map<String, Object> values, String secretField) {
}
