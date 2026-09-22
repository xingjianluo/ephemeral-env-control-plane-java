package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemorySubjectDirectory implements SubjectDirectory {

    private final ConcurrentMap<String, Subject> subjects = new ConcurrentHashMap<>();

    @Override
    public Optional<Subject> resolve(String principal) {
        if (principal == null || principal.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(subjects.get(principal.trim()));
    }

    public void register(Subject subject) {
        subjects.put(subject.principal(), subject);
    }
}
