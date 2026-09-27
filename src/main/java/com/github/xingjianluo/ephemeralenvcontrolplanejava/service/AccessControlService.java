package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ForbiddenException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.UnauthorizedException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ValidationException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于“租户 + 角色 + 所有者”的访问控制。
 *
 * 权限矩阵（跨租户一律拒绝）：
 * - VIEW_ENV：TENANT_ADMIN 任意环境；OWNER 仅本人环境；VIEWER 可读本租户环境；
 * - VIEW_SENSITIVE_CONFIG / RENEW / SUSPEND / RESUME / RELEASE：
 *   TENANT_ADMIN 任意；OWNER 仅本人；VIEWER 禁止。
 *
 * 越权异常只给通用说明，不携带环境是否存在等内部细节（不存在由调用方在鉴权前
 * 以 NOT_FOUND 回应，避免通过错误差异探测他租户资源）。
 */
@Service
public class AccessControlService {

    private record Key(String name, String tenant) {
    }

    private final Map<Key, Subject> registry = new ConcurrentHashMap<>();

    /** 注册或更新主体（幂等）。 */
    public Subject register(String name, String tenantId, String role) {
        if (name == null || name.isBlank() || tenantId == null || tenantId.isBlank()) {
            throw new ValidationException("subject name and tenant are required");
        }
        Role r = parseRole(role);
        Subject s = new Subject(name, tenantId, r);
        registry.put(new Key(name, tenantId), s);
        return s;
    }

    /**
     * 解析请求头声明的主体：必须已注册，且声明角色与注册角色一致。
     * 本地模拟：不接真实凭据系统，但未知主体/伪造角色一律 401。
     */
    public Subject resolve(String name, String tenantId, String role) {
        Key key = new Key(name, tenantId);
        Subject registered = registry.get(key);
        if (registered == null) {
            throw new UnauthorizedException("unregistered subject");
        }
        Role declared = parseRole(role);
        if (declared != registered.role()) {
            throw new UnauthorizedException("declared role does not match registered role");
        }
        return registered;
    }

    private Role parseRole(String role) {
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown role: " + role);
        }
    }

    public void requireTenant(Subject subject, String tenantId) {
        if (!subject.tenantId().equals(tenantId)) {
            // 通用文案：不暴露目标租户任何信息。
            throw new ForbiddenException("access denied");
        }
    }

    public void require(Subject subject, Environment env, Permission permission) {
        requireTenant(subject, env.getTenantId());
        boolean allowed = switch (permission) {
            case VIEW_ENV -> switch (subject.role()) {
                case TENANT_ADMIN, VIEWER -> true;
                case OWNER -> env.getOwner().equals(subject.name());
            };
            case VIEW_SENSITIVE_CONFIG, RENEW, SUSPEND, RESUME, RELEASE -> switch (subject.role()) {
                case TENANT_ADMIN -> true;
                case OWNER -> env.getOwner().equals(subject.name());
                case VIEWER -> false;
            };
        };
        if (!allowed) {
            throw new ForbiddenException("access denied for permission " + permission);
        }
    }
}
