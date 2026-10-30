package com.z1812.drawsite.docker;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static com.z1812.drawsite.docker.DockerDtos.*;

@Service
public class InfrastructureSettings {
    private final ContainerMapper mapper;
    private final JsonMapper json = JsonMapper.builder().build();
    public InfrastructureSettings(ContainerMapper mapper) { this.mapper = mapper; }
    public Config get() {
        mapper.initConfig(json.writeValueAsString(Config.defaults()));
        return json.readValue(mapper.config(), Config.class);
    }
    public Config lock() { get(); return json.readValue(mapper.lockConfig(), Config.class); }
    public ConfigView view() { return new ConfigView(get()); }
    @Transactional public ConfigView save(Config incoming) {
        lock();
        if (incoming.maxRunning() > incoming.maxContainers()) throw new IllegalArgumentException("运行配额不能大于总容器配额");
        if (incoming.portStart() >= incoming.portEnd()) throw new IllegalArgumentException("端口范围至少包含两个端口");
        String host = incoming.publicHost().strip();
        if (!host.matches("[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?")) throw new IllegalArgumentException("公网主机只填写IPv4或域名，不包含协议、端口和路径");
        mapper.configSave(json.writeValueAsString(incoming));
        return view();
    }
}
