package com.github.xingjianluo.ephemeralenvcontrolplanejava.web;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.UnauthorizedException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.AccessControlService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 从请求头 X-Subject / X-Tenant / X-Role 解析访问主体。
 * 本地模拟用途，不依赖真实凭据系统。
 */
@Component
public class SubjectResolver implements HandlerMethodArgumentResolver {

    private final AccessControlService accessControlService;

    public SubjectResolver(AccessControlService accessControlService) {
        this.accessControlService = accessControlService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Subject.class;
    }

    @Override
    public Subject resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String name = request.getHeader("X-Subject");
        String tenant = request.getHeader("X-Tenant");
        String role = request.getHeader("X-Role");
        if (name == null || tenant == null || role == null) {
            throw new UnauthorizedException("missing subject headers (X-Subject/X-Tenant/X-Role)");
        }
        return accessControlService.resolve(name, tenant, role);
    }
}
