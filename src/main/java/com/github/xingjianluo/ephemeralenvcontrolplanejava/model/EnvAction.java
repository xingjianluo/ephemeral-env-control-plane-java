package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/** State-machine actions recorded in the audit trail. */
public enum EnvAction {
    APPLY,
    START_PROVISION,
    MARK_READY,
    FAIL,
    RENEW,
    EXPIRE,
    START_RELEASE,
    COMPLETE_RELEASE
}
