package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/** 访问主体：某租户下的具名成员及其角色。 */
public record Subject(String name, String tenantId, Role role) {
}
