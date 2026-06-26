# LegacyGraph 部署文档

## 📋 目录

- [环境要求](#环境要求)
- [依赖服务部署](#依赖服务部署)
- [数据库初始化](#数据库初始化)
- [后端服务部署](#后端服务部署)
- [前端部署](#前端部署)
- [生产环境部署](#生产环境部署)
- [常见问题](#常见问题)

---

## 环境要求

### 硬件要求

| 环境 | CPU | 内存 | 磁盘 |
|------|-----|------|------|
| 开发环境 | 4 核 | 8 GB | 50 GB |
| 生产环境 | 8 核 | 16 GB | 200 GB |

### 软件要求

| 软件 | 版本要求 | 说明 |
|------|---------|------|
| Docker | 20.10+ | 用于启动依赖服务 |
| Docker Compose | 1.29+ | 容器编排 |
| JDK | 21+ | 后端运行环境 |
| Node.js | 18+ | 前端构建运行 |
| Maven | 3.8+ | 后端构建 |
| PostgreSQL | 18+ | 关系数据库（带 pgvector 扩展） |
| Neo4j | 5.x | 图数据库 |
| Redis | 7+ | 缓存 |
| MinIO | latest | 对象存储 |

---

## 依赖服务部署

### 方式一：Docker Compose 一键启动（推荐）

LegacyGraph 提供了完整的 Docker Compose 配置，包含所有依赖服务。

```bash
# 进入部署目录
cd deploy

# 启动所有依赖服务
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看服务日志
docker-compose logs -f postgres
docker-compose logs -f neo4j
docker-compose logs -f redis
docker-compose logs -f minio
```

启动后各服务地址：

| 服务 | 地址 | 用户名/密码 | 说明 |
|------|------|------------|------|
| PostgreSQL | `localhost:5432` | `legacy_graph` / `legacy_graph` | 关系数据库 |
| Neo4j Browser | http://localhost:7474 | `neo4j` / `password` | 图数据库控制台 |
| Neo4j Bolt | `bolt://localhost:7687` | - | 图数据库连接 |
| MinIO Console | http://localhost:9001 | `minio` / `minio123456` | 对象存储控制台 |
| MinIO API | http://localhost:9000 | - | 对象存储 API |
| Redis | `localhost:6379` | - | 缓存服务 |

### 方式二：独立部署

如果需要独立部署各个服务，请参考以下配置：

#### PostgreSQL (带 pgvector)

```bash
# 使用 pgvector 官方镜像
docker run -d \
  --name legacygraph-postgres \
  -e POSTGRES_DB=legacy_graph \
  -e POSTGRES_USER=legacy_graph \
  -e POSTGRES_PASSWORD=legacy_graph \
  -p 5432:5432 \
  -v postgres-data:/var/lib/postgresql/data \
  pgvector/pgvector:pg18
```

#### Neo4j

```bash
docker run -d \
  --name legacygraph-neo4j \
  -e NEO4J_AUTH=neo4j/password \
  -e NEO4J_PLUGINS='["apoc"]' \
  -e NEO4J_dbms_memory_heap_initial__size=512m \
  -e NEO4J_dbms_memory_heap_max__size=2G \
  -p 7474:7474 \
  -p 7687:7687 \
  -v neo4j-data:/data \
  neo4j:5.26
```

#### Redis

```bash
docker run -d \
  --name legacygraph-redis \
  -p 6379:6379 \
  -v redis-data:/data \
  redis:7-alpine
```

#### MinIO

```bash
docker run -d \
  --name legacygraph-minio \
  -e MINIO_ROOT_USER=minio \
  -e MINIO_ROOT_PASSWORD=minio123456 \
  -p 9000:9000 \
  -p 9001:9001 \
  -v minio-data:/data \
  minio/minio:latest server /data --console-address ":9001"
```

---

## 数据库初始化

### 执行顺序

> **重要**：必须按顺序执行，先执行基础表，再执行 LLM 增强表

```bash
# 1. 执行基础表结构
psql -h localhost -p 5432 -U legacy_graph -d legacy_graph < docs/sql/init.sql

# 2. 执行 LLM 集成表和字段增强（包含向量表、Prompt模板等）
psql -h localhost -p 5432 -U legacy_graph -d legacy_graph < docs/sql/llm_integration.sql
```

### 验证表结构

```sql
-- 查看所有表
SELECT table_name FROM information_schema.tables WHERE table_schema = 'public';

-- 应该包含以下表：
-- lg_project, lg_scan_version, lg_scan_task, lg_fact
-- lg_graph_node, lg_graph_edge, lg_evidence, lg_node_evidence, lg_edge_evidence
-- lg_doc_chunk, lg_test_case, lg_test_assertion, lg_test_result, lg_review_record
-- lg_vector_document, lg_prompt_template, lg_llm_provider, lg_prompt_run
```

### 验证扩展

```sql
-- 验证 pgvector 扩展已安装
SELECT * FROM pg_extension WHERE extname = 'vector';
```

---

## 后端服务部署

### 配置文件

复制并修改配置文件：

```bash
cd backend
cp src/main/resources/application.yml src/main/resources/application-local.yml
```

修改 `application-local.yml` 中的配置：

```yaml
server:
  port: 8080
  servlet:
    context-path: /api

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/legacy_graph
    username: legacy_graph
    password: legacy_graph
    driver-class-name: org.postgresql.Driver

  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: password

  data:
    redis:
      host: localhost
      port: 6379
      password:

minio:
  endpoint: http://localhost:9000
  access-key: minio
  secret-key: minio123456
  bucket-name: legacygraph

llm:
  provider: openai
  openai:
    api-key: ${OPENAI_API_KEY:your-api-key-here}
    model: gpt-4o
    base-url: https://api.openai.com/v1
  embedding:
    model: text-embedding-3-small
    dimensions: 1536

logging:
  level:
    io.github.legacygraph: DEBUG
```

### 构建和运行

```bash
# 进入后端目录
cd backend

# 构建项目
mvn clean package -DskipTests

# 运行项目（使用本地配置）
java -jar target/legacygraph-backend.jar --spring.profiles.active=local

# 或者使用 Maven 直接运行
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 验证后端服务

```bash
# 健康检查
curl http://localhost:8080/api/actuator/health

# 查看 Swagger 文档
open http://localhost:8080/api/swagger-ui.html
```

### 后端服务目录结构

```
backend/
├── target/
│   └── legacygraph-backend.jar    # 构建产物
├── src/main/resources/
│   ├── application.yml            # 基础配置
│   ├── application-dev.yml        # 开发环境配置
│   ├── application-prod.yml       # 生产环境配置
│   └── mapper/                    # MyBatis XML 映射文件
└── logs/                          # 日志目录
```

---

## 前端部署

### 开发环境

```bash
# 进入前端目录
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

前端地址：http://localhost:3000

### 生产环境构建

```bash
# 构建生产版本
npm run build

# 构建产物在 dist 目录
ls -la dist/
```

### Nginx 部署

安装 Nginx 并配置：

```nginx
server {
    listen 80;
    server_name your-domain.com;

    # 前端静态文件
    location / {
        root /path/to/legacygraph/frontend/dist;
        try_files $uri $uri/ /index.html;
        index index.html;
    }

    # 后端 API 代理
    location /api/ {
        proxy_pass http://localhost:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # 静态资源缓存
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg)$ {
        root /path/to/legacygraph/frontend/dist;
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
```

### Docker 部署前端

创建 `frontend/Dockerfile`：

```dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

构建并运行：

```bash
cd frontend
docker build -t legacygraph-frontend .
docker run -d -p 80:80 --name legacygraph-frontend legacygraph-frontend
```

---

## 生产环境部署

### 系统服务配置

创建 Systemd 服务文件 `/etc/systemd/system/legacygraph-backend.service`：

```ini
[Unit]
Description=LegacyGraph Backend Service
After=network.target postgresql.service neo4j.service redis.service minio.service

[Service]
Type=simple
User=legacygraph
WorkingDirectory=/opt/legacygraph/backend
ExecStart=/usr/bin/java -jar target/legacygraph-backend.jar --spring.profiles.active=prod
Restart=always
RestartSec=10
StandardOutput=journal+console
StandardError=journal+console

[Install]
WantedBy=multi-user.target
```

启动服务：

```bash
# 重载配置
systemctl daemon-reload

# 启动服务
systemctl start legacygraph-backend

# 设置开机自启
systemctl enable legacygraph-backend

# 查看状态
systemctl status legacygraph-backend

# 查看日志
journalctl -u legacygraph-backend -f
```

### 反向代理配置

推荐使用 Nginx 作为反向代理，配置 HTTPS：

```nginx
server {
    listen 443 ssl http2;
    server_name legacygraph.your-domain.com;

    # SSL 证书配置
    ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;

    # 安全头
    add_header X-Frame-Options DENY;
    add_header X-Content-Type-Options nosniff;
    add_header X-XSS-Protection "1; mode=block";

    # 前端
    location / {
        root /opt/legacygraph/frontend/dist;
        try_files $uri $uri/ /index.html;
        index index.html;
    }

    # 后端 API
    location /api/ {
        proxy_pass http://localhost:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 300s;
        proxy_send_timeout 300s;
        proxy_read_timeout 300s;
    }

    # 文件上传大小限制
    client_max_body_size 500M;
}

# HTTP 重定向到 HTTPS
server {
    listen 80;
    server_name legacygraph.your-domain.com;
    return 301 https://$server_name$request_uri;
}
```

### 日志配置

配置 Logback 日志滚动策略：

```xml
<!-- src/main/resources/logback-spring.xml -->
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/legacygraph.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/legacygraph.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>10GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

---

## 常见问题

### Q: PostgreSQL 连接失败？

A: 检查：
1. Docker 容器是否运行：`docker ps | grep postgres`
2. 端口是否被占用：`lsof -i :5432`
3. 用户名密码是否正确

### Q: Neo4j 连接超时？

A: 检查：
1. Neo4j 容器是否正常启动
2. 7687 端口是否开放
3. 首次启动可能需要 1-2 分钟初始化

### Q: pgvector 扩展安装失败？

A: 确保使用的是 `pgvector/pgvector:pg18` 镜像，而不是标准 PostgreSQL 镜像。

### Q: 前端访问后端 API 跨域？

A: 开发环境已在 Vite 配置了代理，生产环境需要通过 Nginx 反向代理。

### Q: 测试执行失败？

A: 检查：
1. 测试环境网络连通性
2. 目标服务是否正常运行
3. 测试白名单配置是否正确

### Q: LLM API 调用失败？

A: 检查：
1. API Key 是否配置正确
2. 网络是否可以访问 OpenAI API
3. 是否有额度限制

---

## 备份与恢复

### 数据库备份

```bash
# 备份 PostgreSQL
pg_dump -h localhost -p 5432 -U legacy_graph legacy_graph > backup_legacygraph_$(date +%Y%m%d).sql

# 备份 Neo4j
docker exec legacygraph-neo4j neo4j-admin database dump neo4j --to-path=/data/backup

# 备份 MinIO
mc mirror local/legacygraph ./minio-backup/
```

### 数据库恢复

```bash
# 恢复 PostgreSQL
psql -h localhost -p 5432 -U legacy_graph legacy_graph < backup_legacygraph_xxxxxx.sql

# 恢复 Neo4j
docker exec legacygraph-neo4j neo4j-admin database load neo4j --from-path=/data/backup
```

---

## 监控与告警

### 健康检查端点

```
GET /api/actuator/health          # 健康状态
GET /api/actuator/info            # 应用信息
GET /api/actuator/metrics         # 指标信息
```

### 推荐监控工具

- **Prometheus + Grafana** - 系统和应用指标监控
- **ELK Stack** - 日志收集和分析
- **Alertmanager** - 告警通知

---

## 性能优化建议

1. **数据库**：
   - 为常用查询字段添加索引
   - 定期 VACUUM 和 ANALYZE
   - 配置合适的连接池大小

2. **Neo4j**：
   - 根据数据量调整堆内存大小
   - 配置页缓存大小
   - 定期优化图谱索引

3. **后端**：
   - 启用 Gzip 压缩
   - 配置合理的线程池大小
   - 大文件上传使用分片上传

4. **前端**：
   - 启用资源压缩
   - 配置 CDN 加速
   - 使用路由懒加载
