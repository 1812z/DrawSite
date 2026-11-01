<div align="center">

<img src="docs/assets/logo.svg" width="120" height="120" alt="绘站 Logo" />

# 绘站 · DrawSite

**从一句需求到应用开发、测试与发布的 AI 开发平台**

[![GitHub](https://img.shields.io/badge/GitHub-DrawSite-black?style=flat-square&logo=github)](https://github.com/1812z/DrawSite)
![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.1-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white)
![Vue](https://img.shields.io/badge/Frontend-Vue_3-4FC08D?style=flat-square&logo=vuedotjs&logoColor=white)

[后端仓库](https://github.com/1812z/DrawSite)
[前端仓库](https://github.com/1812z/DrawSite_Web)

</div>

## 功能

- **AI 开发**：多模型对话、思考过程、文件编辑、工具执行与需求询问，SSE 增量输出，刷新后恢复运行任务
- **项目管理**：独立工作区、多会话、开发进度、文档、Git 提交与项目打包下载
- **上下文与记忆**：用量估算、自动及手动压缩、用户全局记忆与项目 `AGENTS.md`
- **预览与圈选**：嵌入预览，支持鼠标和触屏选中网页元素，携带定位信息发起修改
- **测试与发布**：严格 OpenAPI 契约、JSON API 测试报告、静态网站快照发布及容器 HTTP 服务代理
- **平台管理**：账号权限、模型配置、Docker 模板与资源配额、容器生命周期管理

## 技术栈

后端： Java 21、Spring Boot、LangChain4j、MyBatis-Flex、Sa-Token、PostgreSQL、Flyway 与 JGit

前端：使用 Vue 3、TypeScript、Vite 和 Tailwind CSS。当前采用单实例进程内任务状态与 SSE 事件通知，无需 Redis。

## 部署

### 1. 准备环境

- Java 21、PostgreSQL，创建数据库与对应用户
- 模型服务需兼容 OpenAI Chat Completions；使用工具开发的模型需支持结构化工具调用
- 容器构建、测试及服务发布需要 Docker Engine 和 Docker CLI

### 2. 启动后端

新建 docker-compose.yaml

```yaml
networks:
  1panel-network:
    external: true
services:
  java:
    command: bash /run.sh
    container_name: ${CONTAINER_NAME}
    build:
      context: /home/DrawSite
      dockerfile: Dockerfile
      args:
        JAVA_VERSION: ${JAVA_VERSION}
    image: drawsite-java:local
    labels:
      createdBy: Apps
    networks:
      - 1panel-network
    extra_hosts:
      - "host.docker.internal:host-gateway"
    ports:
      - ${HOST_IP_0}:${HOST_PORT_0}:${CONTAINER_PORT_0}/tcp
    restart: on-failure:5
    environment:
      WORKSPACE_ROOT: /home/DrawSite/data/workspaces
      GATEWAY_DIRECTORY: /home/DrawSite/data/gateway
      GATEWAY_CONTAINER: drawsite-gateway

    volumes:
      - ${CODE_DIR}:/app
      - /home/DrawSite/data:/home/DrawSite/data
      - ./run.sh:/run.sh
      - ./.env:/.env
      - /var/run/docker.sock:/var/run/docker.sock
    working_dir: /app

```
首次启动由 Flyway 自动执行数据库迁，需提前配置好数据库

### 3. 部署前端与反向代理

在配套前端项目 `DrawSite_Web` 中运行 `npm ci`、`npm run build`，将生成的 `dist` 部署到 Nginx / OpenResty 网站目录。

以下配置放入网站的 `server` 中；后端地址按实际网络环境调整，`proxy_pass` 

```nginx
location / {
    try_files $uri $uri/ /index.html;
}

location ~ ^/(api|sites|services)/ {
    proxy_pass http://127.0.0.1:9988;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    gzip off;
    proxy_read_timeout 3600s;
}
```
