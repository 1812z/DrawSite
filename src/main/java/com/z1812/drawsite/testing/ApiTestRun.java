package com.z1812.drawsite.testing;

import com.mybatisflex.annotation.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Table("api_test_run")
public class ApiTestRun {
    @Id private String id;
    private String applicationId;
    private String title;
    private String status;
    private int total;
    private int passed;
    private int completed;
    private String reportJson;
    private OffsetDateTime createdAt;
    private OffsetDateTime finishedAt;
}
