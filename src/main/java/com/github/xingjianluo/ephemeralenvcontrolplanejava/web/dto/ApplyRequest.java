package com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto;

import java.util.Map;

public record ApplyRequest(String name, String templateId, String version,
                           Map<String, String> parameters, Long ttlSeconds) {
}
