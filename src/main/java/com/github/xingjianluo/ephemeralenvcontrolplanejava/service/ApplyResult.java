package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;

/** Outcome of an apply call: true when a new environment was created. */
public record ApplyResult(Environment environment, boolean created) {
}
