package com.github.xingjianluo.ephemeralenvcontrolplanejava.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** 注册 Subject 参数解析器。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SubjectResolver subjectResolver;

    public WebConfig(SubjectResolver subjectResolver) {
        this.subjectResolver = subjectResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(subjectResolver);
    }
}
