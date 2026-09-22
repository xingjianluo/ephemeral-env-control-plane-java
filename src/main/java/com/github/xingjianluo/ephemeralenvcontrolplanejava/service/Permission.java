package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

/** 访问权限：查看环境 / 查看敏感配置 / 续期 / 回收。 */
public enum Permission {
    VIEW_ENV,
    VIEW_SENSITIVE_CONFIG,
    RENEW,
    RELEASE
}
