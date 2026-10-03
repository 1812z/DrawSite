package com.z1812.drawsite.settings;

import org.apache.ibatis.annotations.*;
import java.util.List;
import com.z1812.drawsite.settings.SettingsDtos.SettingRow;

@Mapper
public interface SettingsMapper {
    @Select("SELECT setting_key, CAST(setting_value AS VARCHAR) AS value_json, version, updated_at FROM platform_setting ORDER BY setting_key")
    List<SettingRow> readAll();

    @Select("SELECT setting_key, CAST(setting_value AS VARCHAR) AS value_json, version, updated_at FROM platform_setting ORDER BY setting_key FOR UPDATE")
    List<SettingRow> lockAll();

    @Select("SELECT CAST(setting_value AS VARCHAR) FROM platform_setting WHERE setting_key = 'registration.enabled'")
    String registrationEnabled();

    @Update("UPDATE platform_setting SET setting_value = CAST(#{json} AS JSONB), version = version + 1, updated_by = #{userId}, updated_at = CURRENT_TIMESTAMP WHERE setting_key = #{key} AND version = #{version}")
    int update(@Param("key") String key, @Param("json") String json,
               @Param("version") long version, @Param("userId") String userId);
}
