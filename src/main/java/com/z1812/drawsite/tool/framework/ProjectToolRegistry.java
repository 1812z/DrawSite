package com.z1812.drawsite.tool.framework;

import com.z1812.drawsite.common.exception.BusinessException;
import dev.langchain4j.agent.tool.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.lang.reflect.*;
import java.util.*;

@Component
public class ProjectToolRegistry {
    private final List<ProjectToolModule> modules;
    private final ToolGuard guard;
    private final JsonMapper json = JsonMapper.builder().build();
    public ProjectToolRegistry(List<ProjectToolModule> modules, ToolGuard guard) {
        this.modules = modules; this.guard = guard;
    }

    public record ToolView(String id, String name, String description, boolean builtin, boolean required) {}

    public List<ToolView> catalog() {
        var result = new ArrayList<ToolView>();
        modules.forEach(module -> result.add(new ToolView(module.id(), module.name(), module.description(), true,
                Set.of("file", "clarification", "progress").contains(module.id()))));
        return result.stream().sorted(Comparator.comparing(ToolView::name)).toList();
    }

    public Set<String> select(List<String> names) {
        var available = catalog().stream().map(ToolView::id).collect(java.util.stream.Collectors.toSet());
        // 兼容原先按实际函数名选择工具的请求
        available.addAll(bind(new ToolContext(null, null, null, () -> true)).bindings.keySet());
        if (names == null) return available;
        var selected = new HashSet<>(names);
        if (!available.containsAll(selected)) {
            throw new BusinessException("TOOL_NOT_AVAILABLE", "选择了不存在的工具，请刷新工具列表", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        return Set.copyOf(selected);
    }

    public List<ToolView> catalog(String app) {
        return catalog();
    }

    public Session bind(ToolContext context, Set<String> selected) {
        return bindModules(context, selected);
    }

    public Session bind(ToolContext context) {
        return bindModules(context, null);
    }

    private Session bindModules(ToolContext context, Set<String> selected) {
        var methods = new HashMap<String, Binding>();
        var specs = new ArrayList<ToolSpecification>();
        for (var module : modules) {
            if (!module.available(context)) continue;
            Object tools = module.bind(context);
            var moduleSpecs = ToolSpecifications.toolSpecificationsFrom(tools).stream()
                    .filter(spec -> selected == null || Set.of("file", "clarification", "progress").contains(module.id()) || selected.contains(module.id()) || selected.contains(spec.name())).toList();
            specs.addAll(moduleSpecs);
            for (var method : tools.getClass().getMethods()) {
                var annotation = method.getAnnotation(Tool.class);
                if (annotation == null) continue;
                String name = annotation.name().isBlank() ? method.getName() : annotation.name();
                if (moduleSpecs.stream().noneMatch(spec -> spec.name().equals(name))) continue;
                if (methods.putIfAbsent(name, new Binding(tools, method)) != null) throw new IllegalStateException("重复工具名: " + name);
            }
        }
        return new Session(context, List.copyOf(specs), methods);
    }

    private record Binding(Object instance, Method method) {}

    public class Session {
        private final ToolContext context;
        private final List<ToolSpecification> specifications;
        private final Map<String, Binding> bindings;
        private Session(ToolContext context, List<ToolSpecification> specifications, Map<String, Binding> bindings) {
            this.context = context; this.specifications = specifications; this.bindings = bindings;
        }
        public List<ToolSpecification> specifications() { return specifications; }

        public String execute(ToolExecutionRequest request) {
            long start = System.nanoTime();
            String code = "OK";
            try {
                var binding = bindings.get(request.name());
                if (binding == null) throw new IllegalArgumentException("工具不存在");
                if (request.arguments() == null || request.arguments().length() > 3 * 1024 * 1024) throw new IllegalArgumentException("工具参数不合法");
                var args = json.readTree(request.arguments());
                if (!args.isObject()) throw new IllegalArgumentException("工具参数必须是对象");
                var parameters = binding.method().getParameters();
                Object[] values = new Object[parameters.length];
                var names = new HashSet<String>();
                for (int i = 0; i < parameters.length; i++) {
                    var parameter = parameters[i];
                    var annotation = parameter.getAnnotation(P.class);
                    String name = annotation != null && !annotation.name().isBlank() ? annotation.name() : parameter.getName();
                    names.add(name);
                    var value = args.get(name);
                    if (value == null || value.isNull()) {
                        if (annotation != null && !annotation.required()) { values[i] = null; continue; }
                        throw new IllegalArgumentException("缺少参数: " + name);
                    }
                    values[i] = json.convertValue(value, json.getTypeFactory().constructType(parameter.getParameterizedType()));
                }
                for (String name : args.propertyNames()) if (!names.contains(name)) throw new IllegalArgumentException("未知参数: " + name);
                Object result = guard.execute(context, () -> {
                    try { return binding.method().invoke(binding.instance(), values); }
                    catch (InvocationTargetException exception) {
                        if (exception.getCause() instanceof Exception cause) throw cause;
                        throw exception;
                    }
                });
                // 用户等待放在事务之外，避免长期持有应用锁
                if (result instanceof DeferredToolResult deferred) result = deferred.await(context);
                return json.writeValueAsString(Map.of("code", "OK", "data", result));
            } catch (Exception exception) {
                code = "TOOL_FAILED";
                Throwable cause = exception instanceof ToolGuard.ToolOperationException ? exception.getCause() : exception;
                String message = "工具执行失败，请检查参数和文件格式";
                if (cause instanceof BusinessException business) { code = business.getCode(); message = business.getMessage(); }
                else if (cause instanceof IllegalArgumentException) message = cause.getMessage();
                return json.writeValueAsString(Map.of("code", code, "message", message));
            } finally {
                org.slf4j.LoggerFactory.getLogger(ProjectToolRegistry.class).info("Tool {} run={} -> {} {}ms", request.name(), context.runId(), code,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        }
    }
}
