package com.z1812.drawsite.testing;

import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ApiTestMapper extends BaseMapper<ApiTestRun> {
    @Select("SELECT * FROM api_test_run WHERE application_id=#{id} ORDER BY created_at DESC LIMIT 50")
    List<ApiTestRun> history(String id);
    @Select("SELECT COUNT(*) FROM api_test_run WHERE application_id=#{id} AND status='RUNNING'")
    int running(String id);
    @Update("UPDATE api_test_run SET status='FAILED',finished_at=CURRENT_TIMESTAMP WHERE status='RUNNING'")
    void recover();
}
