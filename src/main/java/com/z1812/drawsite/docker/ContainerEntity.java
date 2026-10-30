package com.z1812.drawsite.docker;

import com.mybatisflex.annotation.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Table("managed_container")
public class ContainerEntity {
    @Id private String id;
    private String ownerUserId;
    private String applicationId;
    private String templateId;
    private String dockerId;
    private String name;
    private String purpose;
    private String status;
    private int internalPort;
    private Integer hostPort;
    private Integer secondaryHostPort;
    private String publicHost;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createdAt;
    private String error;
    public String getNetworkName() { return "drawsite-net-" + applicationId; }
    public String getInternalUrl() { return "http://" + name + ":" + internalPort; }
    public int getSecondaryInternalPort() { return internalPort == 65535 ? 65534 : internalPort + 1; }
    public String getPublicUrl() { return hostPort == null ? null : "http://" + publicHost + ":" + hostPort; }
    public String getSecondaryPublicUrl() { return secondaryHostPort == null ? null : "http://" + publicHost + ":" + secondaryHostPort; }
    public String getServiceUrl() { return "/services/" + id + "/1/"; }
    public String getSecondaryServiceUrl() { return "/services/" + id + "/2/"; }
}
