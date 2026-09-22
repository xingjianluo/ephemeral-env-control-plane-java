package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.template.RenderedTemplate;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.template.TemplateDescriptor;

import java.util.Map;

/** Versioned configuration templates with stable semantics across versions. */
public interface TemplateService {

    TemplateDescriptor describe(String templateId, String version);

    /**
     * Render for a new environment. Unknown versions/fields are rejected
     * explicitly; missing fields take version-defined defaults.
     */
    RenderedTemplate render(String templateId, String version, Map<String, String> parameters);

    /** Register (or replace) one template version descriptor. */
    void register(TemplateDescriptor descriptor);
}
