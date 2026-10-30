package com.z1812.drawsite.docker;

import org.springframework.stereotype.Component;
import com.z1812.drawsite.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Component
public class DockerEngine {
    public record Result(int exitCode, String output) {}
    public Result run(int timeout, String... args) {
        var command = new ArrayList<String>(); command.add("docker"); command.addAll(List.of(args));
        Process process = null;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process child = process;
            var output = executor.submit(() -> {
                try (var input = child.getInputStream(); var buffer = new java.io.ByteArrayOutputStream()) {
                    byte[] bytes = new byte[8192]; int count;
                    while ((count = input.read(bytes)) >= 0) if (buffer.size() < 200000) buffer.write(bytes, 0, Math.min(count, 200000 - buffer.size()));
                    return buffer.toString(StandardCharsets.UTF_8);
                }
            });
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BusinessException("DOCKER_TIMEOUT", "Docker操作超时", HttpStatus.GATEWAY_TIMEOUT);
            }
            return new Result(process.exitValue(), output.get(5, TimeUnit.SECONDS).strip());
        } catch (BusinessException error) { throw error; }
        catch (java.io.IOException error) { throw new BusinessException("DOCKER_CLI_MISSING", "无法启动docker命令：请在后端Java容器中安装Docker CLI，并挂载docker.sock", HttpStatus.SERVICE_UNAVAILABLE); }
        catch (Exception error) { throw new BusinessException("DOCKER_UNAVAILABLE", "无法访问Docker，请检查安装、服务及运行账号权限", HttpStatus.SERVICE_UNAVAILABLE); }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
    public String checked(int timeout, String... args) {
        var result = run(timeout, args);
        if (result.exitCode() != 0) throw new BusinessException("DOCKER_FAILED", result.output().substring(0, Math.min(result.output().length(), 500)), HttpStatus.BAD_GATEWAY);
        return result.output();
    }
}
