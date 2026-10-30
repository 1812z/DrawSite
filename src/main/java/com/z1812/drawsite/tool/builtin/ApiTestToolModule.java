package com.z1812.drawsite.tool.builtin;

import com.z1812.drawsite.testing.*;
import com.z1812.drawsite.tool.framework.*;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class ApiTestToolModule implements ProjectToolModule {
    private final ApiTestService tests;
    public ApiTestToolModule(ApiTestService tests) { this.tests = tests; }
    public String id() { return "apiTest"; }
    public String name() { return "API测试"; }
    public String description() { return "严格API契约、生成读写测试JSON、执行测试和读取JSON报告"; }
    public Object bind(ToolContext context) { return new Tools(tests, context); }
    public record Tools(ApiTestService tests, ToolContext context) {
        @Tool("读取API测试文件格式说明及严格OpenAPI子集，编写前必须查询")
        public String apiTestFormat() { return """
            契约api/openapi.json仅允许OpenAPI 3.1.0受限子集，根字段openapi/info/paths；info含title/version/description。
            paths的路径下仅HTTP方法get/post/put/patch/delete/head/options。操作仅operationId/summary/description/parameters/requestBody/responses；operationId全局唯一。
            parameters含name/in(path|query|header)/required/schema/description，路径参数required=true；鉴权通过声明Authorization等header参数。
            requestBody含required/content/description；content仅application/json:{schema:...}。responses必须使用精确状态码，每项含description及可选content。
            schema仅type/properties/required/additionalProperties/items/enum/description/minimum/maximum/minLength/maxLength；对象必须properties及additionalProperties=false；不支持$ref、组合schema及外部引用。
            用例api/tests/suite.test.json：{"schemaVersion":"1.0","title":"接口测试","targets":{"backend":{"containerId":"当前项目容器UUID","port":8080,"basePath":"/api"}},"cases":[{"id":"health","title":"健康检查","operationId":"health","target":"backend","path":{},"query":{},"headers":{},"expectedStatus":200,"checks":[{"pointer":"/status","equals":"ok"}]}]}
            cases可含body(JSON值)；checks是JSON Pointer精确值断言。请求method及path从契约取得，不允许用例自行指定未声明API。状态码必须在契约声明；参数名必须在契约声明。可故意省略必填参数或提供无效值测试异常响应。
            多容器在targets定义多个别名，各用例target引用别名；仅当前项目运行容器。port为容器内部端口，不是宿主端口；basePath为服务前缀，若契约path已含/api就不要重复设置。
            先用Docker工具自行启动服务并核实监听端口；执行器在目标项目容器内通过curl及项目网络发起HTTP请求，因此至少执行容器需安装curl，无需公网IP/开放端口。用例串行，不跟随重定向，每请求10秒，响应最多64KiB。
            generateApiTests只生成基础样例，必须读取并按实际业务补充数据、鉴权及边界用例，不代表已验证通过。startApiTests返回runId，随后readApiTestReport轮询读取状态及JSON报告。
            """; }
        @Tool("读取完整API契约JSON及sha256")
        public Object readApiContract() throws Exception { return tests.read(context.applicationId(), ApiDocuments.CONTRACT); }
        @Tool("严格校验并保存API契约JSON，expectedHash使用读取的sha256，新建为NEW")
        public Object writeApiContract(String content, String expectedHash) throws Exception { return tests.writeContract(context.applicationId(), content, expectedHash); }
        @Tool("读取完整测试用例JSON及sha256")
        public Object readApiTests() throws Exception { return tests.read(context.applicationId(), ApiDocuments.SUITE); }
        @Tool("校验并保存测试JSON，targets由AI指定当前项目容器ID/内部端口/basePath，expectedHash来自读取或NEW")
        public Object writeApiTests(String content, String expectedHash) throws Exception { return tests.writeSuite(context.applicationId(), content, expectedHash); }
        @Tool("根据契约生成基础测试JSON，targetJson为{containerId,port,basePath}，expectedHash为现有测试sha256或NEW；之后需读取调整样例")
        public Object generateApiTests(String targetJson, String expectedHash) throws Exception { return tests.generate(context.applicationId(), targetJson, expectedHash); }
        @Tool("发起当前项目API测试，前提有有效契约和测试JSON，AI需提前启动目标服务。异步返回任务ID，使用readApiTestReport查看")
        public Object startApiTests() {
            return (DeferredToolResult) bound -> { if (bound.stopped().getAsBoolean()) throw new IllegalStateException("任务已停止"); return tests.start(bound.applicationId(), bound.ownerUserId()); };
        }
        @Tool("读取当前项目指定测试任务的JSON报告，包括状态、通过数量及每条API失败详情")
        public Object readApiTestReport(String runId) { return tests.report(context.applicationId(), runId); }
    }
}
