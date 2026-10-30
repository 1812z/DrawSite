package com.z1812.drawsite.docker;

import org.springframework.stereotype.Component;

@Component
public class ContainerNetworks {
    private final DockerEngine docker;
    private final ContainerMapper containers;
    public ContainerNetworks(DockerEngine docker, ContainerMapper containers) {
        this.docker = docker; this.containers = containers;
    }
    public String name(String id) { return "drawsite-net-" + java.util.UUID.fromString(id); }
    public String create(String id) {
        String name = name(id);
        var existing = docker.run(10, "network", "inspect", name);
        if (existing.exitCode() != 0) docker.checked(15, "network", "create", "--driver", "bridge", "--label", "drawsite.managed=true", "--label", "drawsite.application=" + id, name);
        return name;
    }
    public void remove(String id) {
        if (containers.all().stream().anyMatch(item -> id.equals(item.getApplicationId()))) return;
        docker.run(10, "network", "rm", name(id));
    }
}
