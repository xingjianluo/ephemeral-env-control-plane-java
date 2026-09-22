package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/** 模拟资源后端中一个已分配资源。 */
public record SimResource(String resourceId, String envId, ResourceSpec spec, long createdAtMillis) {
}
