package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

import java.util.Set;

/** Authenticated principal and the roles it holds in one tenant. */
public record Subject(String principal, String tenantId, Set<Role> roles) {

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
