package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;

/**
 * Owner/role access control.
 *
 * <ul>
 *   <li>OWNER/MEMBER/VIEWER may list and view non-sensitive environment data.</li>
 *   <li>Only the environment owner or an OWNER role may read secret values.</li>
 *   <li>Renew/recycle require the env owner or OWNER role.</li>
 * </ul>
 * Cross-tenant access raises the same generic FORBIDDEN as an unknown env,
 * so denied requests cannot probe for environment existence.
 */
public class DefaultAccessService implements AccessService {

    private final SubjectDirectory directory;

    public DefaultAccessService(SubjectDirectory directory) {
        this.directory = directory;
    }

    @Override
    public Subject requireAuthenticated(String principal) {
        return directory.resolve(principal)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED,
                        "authentication required"));
    }

    @Override
    public void requireView(Subject subject, Environment env) {
        if (!sameTenant(subject, env)) {
            throw forbidden();
        }
        // Any of the three roles grants read access.
    }

    @Override
    public void requireSensitiveView(Subject subject, Environment env) {
        if (!sameTenant(subject, env) || !canMutate(subject, env)) {
            throw forbidden();
        }
    }

    @Override
    public void requireMutate(Subject subject, Environment env) {
        if (!sameTenant(subject, env) || !canMutate(subject, env)) {
            throw forbidden();
        }
    }

    @Override
    public void requireAdmin(Subject subject) {
        if (!subject.hasRole(Role.OWNER)) {
            throw forbidden();
        }
    }

    private static boolean sameTenant(Subject subject, Environment env) {
        return subject.tenantId().equals(env.tenantId());
    }

    private static boolean canMutate(Subject subject, Environment env) {
        return subject.hasRole(Role.OWNER)
                || subject.principal().equals(env.owner());
    }

    private static ApiException forbidden() {
        // Deliberately generic: no hint about env existence or internals.
        return new ApiException(ErrorCode.FORBIDDEN, "access denied");
    }
}
