package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.TemplateIncompatibleException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ValidationException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.RenderedConfig;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.TemplateVersion;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模板版本演进，严格兼容策略：
 *
 * 1. 版本注册时校验：新版本相对其紧邻旧版本只能“新增字段”，
 *    删除字段或修改既有字段默认值都拒绝（保证语义稳定）；敏感字段名也不得变更。
 * 2. 渲染时：版本不存在 -> TEMPLATE_INCOMPATIBLE；
 *    提交了该版本未声明的字段 -> TEMPLATE_INCOMPATIBLE（绝不静默忽略）；
 *    未覆盖字段使用该版本默认值；敏感字段同样严格校验且被识别。
 * 3. 渲染结果在环境就绪时固化，后续模板注册新版本不追溯影响已就绪环境。
 */
@Service
public class TemplateService {

    private final Map<String, TemplateVersion> versions = new ConcurrentHashMap<>();
    private final NavigableMap<String, TemplateVersion> ordered =
            java.util.Collections.synchronizedNavigableMap(new TreeMap<>());

    public synchronized TemplateVersion register(String version, String secretField,
                                                 Map<String, Object> defaults) {
        if (version == null || version.isBlank()) {
            throw new ValidationException("template version is required");
        }
        if (secretField == null || secretField.isBlank()) {
            throw new ValidationException("secretField is required");
        }
        if (defaults == null || defaults.isEmpty()) {
            throw new ValidationException("template defaults must not be empty");
        }
        if (!defaults.containsKey(secretField)) {
            throw new ValidationException("secretField must be present in defaults: " + secretField);
        }
        if (versions.containsKey(version)) {
            throw new ValidationException("template version already registered: " + version);
        }
        // 兼容校验：相对紧邻旧版本只能新增字段，且默认值与敏感字段保持不变。
        String lower = ordered.lowerKey(version);
        if (lower != null) {
            TemplateVersion prev = versions.get(lower);
            if (!prev.secretField().equals(secretField)) {
                throw new TemplateIncompatibleException(
                        "secretField changed from " + prev.secretField() + " to " + secretField
                                + " (version " + version + " vs " + lower + ")");
            }
            for (Map.Entry<String, Object> e : prev.defaults().entrySet()) {
                if (!defaults.containsKey(e.getKey())) {
                    throw new TemplateIncompatibleException(
                            "field removed in new template version: " + e.getKey()
                                    + " (" + lower + " -> " + version + ")");
                }
                Object newValue = defaults.get(e.getKey());
                if (!java.util.Objects.equals(e.getValue(), newValue)) {
                    throw new TemplateIncompatibleException(
                            "default value of existing field changed: " + e.getKey()
                                    + " (" + e.getValue() + " -> " + newValue + "), version " + version);
                }
            }
        }
        TemplateVersion tv = new TemplateVersion(version, secretField, Map.copyOf(defaults));
        versions.put(version, tv);
        ordered.put(version, tv);
        return tv;
    }

    public TemplateVersion requireVersion(String version) {
        TemplateVersion tv = versions.get(version);
        if (tv == null) {
            throw new TemplateIncompatibleException("unsupported template version: " + version);
        }
        return tv;
    }

    /** 严格渲染：未知字段明确拒绝；敏感字段从 overrides/defaults 解析并单独输出。 */
    public RenderedConfig render(String version, Map<String, Object> overrides) {
        TemplateVersion tv = requireVersion(version);
        Map<String, Object> input = overrides == null ? Map.of() : overrides;
        Set<String> allowed = tv.defaults().keySet();
        for (String field : input.keySet()) {
            if (!allowed.contains(field)) {
                throw new TemplateIncompatibleException(
                        "field not supported by template version " + version + ": " + field);
            }
        }
        Map<String, Object> values = new LinkedHashMap<>(tv.defaults());
        values.putAll(input);
        if (values.get(tv.secretField()) == null) {
            throw new ValidationException("secret field value must not be null: " + tv.secretField());
        }
        return new RenderedConfig(version, Map.copyOf(values), tv.secretField());
    }

    /** 从渲染值中提取并移除敏感字段值（供服务层单独存放）。 */
    public String extractSecret(RenderedConfig config) {
        return String.valueOf(config.values().get(config.secretField()));
    }
}
