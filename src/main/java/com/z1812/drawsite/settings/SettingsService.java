package com.z1812.drawsite.settings;

import cn.dev33.satoken.stp.StpUtil;
import com.z1812.drawsite.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import static com.z1812.drawsite.settings.SettingsDtos.*;

@Service
public class SettingsService {
    private static final Set<String> KEYS = Set.of("registration.enabled", "ai.models", "ai.defaultModelId");
    private final SettingsMapper settings;
    private final JsonMapper json = JsonMapper.builder().build();

    public SettingsService(SettingsMapper settings) { this.settings = settings; }

    public boolean isRegistrationEnabled() {
        String value = settings.registrationEnabled();
        if (!"true".equals(value) && !"false".equals(value)) throw invalidStoredSettings();
        return Boolean.parseBoolean(value);
    }

    public PublicSettings publicSettings() {
        var current = decode(settings.readAll());
        return new PublicSettings(current.registrationEnabled(), current.models().stream()
                .filter(model -> Boolean.TRUE.equals(model.enabled()))
                .map(model -> new PublicModel(model.id(), model.displayName(), model.supportsTools())).toList(),
                current.defaultModelId());
    }

    public AdminSettings adminSettings() {
        StpUtil.checkRole("ADMIN");
        return decode(settings.readAll());
    }

    @Transactional
    public AdminSettings update(UpdateRequest request) {
        StpUtil.checkRole("ADMIN");
        if (!request.versions().keySet().equals(KEYS)) throw invalid("必须提供全部配置版本");
        validateModels(request.models(), request.defaultModelId());
        var current = decode(settings.lockAll());
        if (!current.versions().equals(request.versions())) {
            throw new BusinessException("SETTINGS_CONFLICT", "配置已被更新，请刷新后重试", HttpStatus.CONFLICT);
        }
        String userId = StpUtil.getLoginIdAsString();
        write("registration.enabled", request.registrationEnabled(), current, userId);
        write("ai.models", request.models(), current, userId);
        write("ai.defaultModelId", request.defaultModelId(), current, userId);
        return decode(settings.readAll());
    }

    private void write(String key, Object value, AdminSettings current, String userId) {
        if (settings.update(key, json.writeValueAsString(value), current.versions().get(key), userId) != 1) {
            throw new BusinessException("SETTINGS_CONFLICT", "配置已被更新，请刷新后重试", HttpStatus.CONFLICT);
        }
    }

    private AdminSettings decode(List<SettingRow> rows) {
        try {
            var values = rows.stream().collect(Collectors.toMap(SettingRow::settingKey, SettingRow::valueJson));
            if (!values.keySet().equals(KEYS)) throw invalidStoredSettings();
            var enabled = json.readTree(values.get("registration.enabled"));
            if (!enabled.isBoolean()) throw invalidStoredSettings();
            var models = List.of(json.readValue(values.get("ai.models"), ModelConfig[].class));
            String defaultId = json.readValue(values.get("ai.defaultModelId"), String.class);
            var versions = rows.stream().collect(Collectors.toMap(SettingRow::settingKey, SettingRow::version));
            return new AdminSettings(enabled.asBoolean(), models, defaultId, versions);
        } catch (BusinessException exception) { throw exception; }
        catch (RuntimeException exception) { throw invalidStoredSettings(); }
    }

    private void validateModels(List<ModelConfig> models, String defaultId) {
        var ids = new HashSet<String>();
        for (var model : models) {
            if (!ids.add(model.id())) throw invalid("模型标识不能重复");
            try {
                URI uri = URI.create(model.baseUrl());
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getFragment() != null || uri.getQuery() != null) {
                    throw invalid("模型地址必须是无凭据、查询参数和片段的 HTTP(S) 地址");
                }
            } catch (IllegalArgumentException exception) { throw invalid("模型地址不合法"); }
        }
        boolean anyEnabled = models.stream().anyMatch(model -> Boolean.TRUE.equals(model.enabled()));
        if (defaultId == null) {
            if (anyEnabled) throw invalid("启用模型后必须选择默认模型");
        } else if (models.stream().noneMatch(model -> model.id().equals(defaultId) && Boolean.TRUE.equals(model.enabled()))) {
            throw invalid("默认模型必须存在且启用");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }
    private BusinessException invalidStoredSettings() {
        return new BusinessException("SETTINGS_INVALID", "平台配置缺失或格式不正确", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
