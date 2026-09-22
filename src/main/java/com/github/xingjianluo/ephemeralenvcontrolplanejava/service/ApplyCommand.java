package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;

import java.util.Map;

/** Apply request as understood by the service layer. */
public record ApplyCommand(
        Subject subject,
        String name,
        String templateId,
        String templateVersion,
        Map<String, String> parameters,
        Long requestedTtlSeconds) {
}
