package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;

/** Owner/role based access checks. Failures are generic (no existence leak). */
public interface AccessService {

    Subject requireAuthenticated(String principal);

    void requireView(Subject subject, Environment env);

    void requireSensitiveView(Subject subject, Environment env);

    void requireMutate(Subject subject, Environment env);

    void requireAdmin(Subject subject);
}
