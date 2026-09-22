package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;

import java.util.Optional;

/** Resolves an authenticated principal to its tenant and roles. */
public interface SubjectDirectory {

    Optional<Subject> resolve(String principal);
}
