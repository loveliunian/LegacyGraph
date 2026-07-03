# LegacyGraph 部署文档

## 目录

- [部署架构](#部署架构)
- [环境要求](#环境要求)
- [外部依赖服务](#外部依赖服务)
- [应用 Docker Compose 部署](#应用-docker-compose-部署)
- [数据库初始化](#数据库初始化)
- [本地开发部署](#本地开发部署)
- [生产反向代理](#生产反向代理)
- [验证清单](#验证清单)
- [常见问题](#常见问题)

---

## 部署架构

当前仓库的部署方式是：

```text
User Browser
  -> frontend Nginx container :80
      -> static Vue files
      -> /api/* proxy to backend:8080
  -> backend Spring Boot container :8080
      -> external PostgreSQL
      -> external Neo4j
      -> external Redis
      -> external MinIO
      -> external LLM Provider
```

`deploy/docker-compose.yml` 只构建和启动：

- `legacygraph-backend`
- `legacygraph-frontend`

PostgreSQL、Neo4j、Redis、MinIO 均使用外部服务，连接信息通过 `deploy/.env` 注入。

---

## 环境要求

### 应用服务器

| 环境 | CPU | 内存 | 磁盘 |
|------|-----|------|------|
| 开发/演示 | 4 核 | 8 GB | 50 GB |
| 生产 | 8 核+ | 16 GB+ | 200 GB+ |

### 软件

| 软件 | 版本 | 用途 |
|------|------|------|
| Docker | 20.10+ | 构建和运行容器 |
| Docker Compose | 2.x | 前后端编排 |
| JDK | 21+ | 本地或裸机后端运行 |
| Maven | 3.8+ | 本地后端构建 |
| Node.js | 20+ | 本地前端构建 |
| PostgreSQL | 15+ | 主数据库 |
| pgvector | 与 PostgreSQL 匹配 | 向量能力 |
| Neo4j | 5.x | 图数据库 |
| Redis | 7.x | 缓存和 JWT 黑名单 |
| MinIO | 当前稳定版 | 文件存储 |

---

## 外部依赖服务

### PostgreSQL

要求：

- 创建数据库，例如 `legacy_graph`。
- 创建应用用户并授权。
- 建议提前启用 pgvector。

```sql
CREATE DATABASE legacy_graph;
CREATE USER legacygraph WITH PASSWORD '<strong-password>';
GRANT ALL PRIVILEGES ON DATABASE legacy_graph TO legacygraph;
\c legacy_graph
CREATE EXTENSION IF NOT EXISTS vector;
```

如使用容器临时部署：

```bash
docker run -d \
  --name legacygraph-postgres \
  -e POSTGRES_DB=legacy_graph \
  -e POSTGRES_USER=legacygraph \
  -e POSTGRES_PASSWORD=<strong-password> \
  -p 5432:5432 \
  -v legacygraph-postgres-data:/var/lib/postgresql/data \
  pgvector/pgvector:pg16
```

也可使用更高 PostgreSQL 主版本，前提是 pgvector 镜像或扩展匹配。

### Neo4j

```bash
docker run -d \
  --name legacygraph-neo4j \
  -e NEO4J_AUTH=neo4j/<strong-password> \
  -e NEO4J_PLUGINS='["apoc"]' \
  -p 7474:7474 \
  -p 7687:7687 \
  -v legacygraph-neo4j-data:/data \
  neo4j:5.26
```

### Redis

```bash
docker run -d \
  --name legacygraph-redis \
  -p 6379:6379 \
  -v legacygraph-redis-data:/data \
  redis:7-alpine redis-server --appendonly yes
```

如需密码，使用独立配置文件或启动参数配置，并同步到 `deploy/.env`。

### MinIO

```bash
docker run -d \
  --name legacygraph-minio \
  -e MINIO_ROOT_USER=<access-key> \
  -e MINIO_ROOT_PASSWORD=<secret-key> \
  -p 9000:9000 \
  -p 9001:9001 \
  -v legacygraph-minio-data:/data \
  minio/minio:latest server /data --console-address ":9001"
```

创建 bucket：

```bash
mc alias set legacygraph http://<MINIO_HOST>:9000 <access-key> <secret-key>
mc mb legacygraph/legacy-graph
```

---

## 应用 Docker Compose 部署

### 1. 准备环境变量

在部署机创建 `deploy/.env`。不要把真实 `.env` 提交到代码库。

```bash
cd deploy
vim .env
```

模板：

```dotenv
BACKEND_PORT=8080
FRONTEND_PORT=80

POSTGRES_URL=jdbc:postgresql://<pg-host>:5432/legacy_graph
POSTGRES_USERNAME=legacygraph
POSTGRES_PASSWORD=<postgres-password>

NEO4J_URI=bolt://<neo4j-host>:7687
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=<neo4j-password>

REDIS_HOST=<redis-host>
REDIS_PORT=6379
REDIS_PASSWORD=<redis-password-or-empty>

MINIO_ENDPOINT=http://<minio-host>:9000
MINIO_ACCESS_KEY=<minio-access-key>
MINIO_SECRET_KEY=<minio-secret-key>

JWT_SECRET=<at-least-32-bytes-random-secret>

# LLM 可选；实际运行时还会读取 lg_llm_provider.api_config
OPENAI_API_KEY=<openai-key-or-empty>
DEEPSEEK_API_KEY=<deepseek-key-or-empty>
```

注意：

- Compose 当前会显式要求 PostgreSQL、Neo4j、Redis、MinIO 变量。
- `JWT_SECRET` 生产必须设置，即使代码存在开发默认值也不要依赖。
- LLM Provider 的默认模型和 API Key 最终以 `lg_llm_provider` 表为准。

### 2. 启动应用

```bash
cd deploy
docker compose up --build -d
```

查看状态：

```bash
docker compose ps
docker compose logs -f backend
docker compose logs -f frontend
```

### 3. 停止应用

```bash
cd deploy
docker compose down
```

### 4. 更新应用

```bash
git pull
cd deploy
docker compose up --build -d
```

---

## 数据库初始化

### 自动初始化

后端启动时会自动运行 Flyway：

```text
classpath:db/migration
```

当前脚本：

- `V1__initial_schema.sql`
- `V2__llm_provider_deepseek.sql`
- `V3__fix_schema_gaps.sql`
- `V4__add_deleted_columns.sql`
- `V5__create_missing_tables.sql`
- `V6__add_privacy_and_agent_run.sql`
- `V7__fix_prompt_run_column_types.sql`
- `V8__init_dict_seed_data.sql`
- `V9__create_change_task_tables.sql`
- `V10__dedup_evidence.sql`
- `V11__fix_llm_provider_api_config.sql`
- `V12__seed_prompt_templates.sql`
- `V13__add_scan_version_stats.sql`
- `V14__add_change_task_version.sql`
- `V15__create_knowledge_claim_gap_task.sql`
- `V16__create_domain_ontology.sql`
- `V17__switch_embedding_to_siliconflow.sql`
- `V18__fix_prompt_output_schema.sql`
- `V19__switch_embedding_to_ollama.sql`
- `V20__create_source_asset_snapshot.sql`
- `V21__create_graph_write_intent.sql`
- `V22__create_tool_run_tables.sql`
- `V23__rebuild_vector_document.sql`
- `V24__add_scan_task_progress.sql`
- `V25__add_dict_items.sql`
- `V26__fix_scan_task_progress.sql`
- `V27__add_db_connection_schema_fp.sql`
- `V28__create_ai_scan_job.sql`
- `V29__create_qa_tables.sql`
- `V30__create_semantic_cache.sql`

### 初始化验证

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

应看到 `V1` 到 `V30` 且 `success = true`。

检查核心表：

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

检查 pgvector：

```sql
SELECT extname FROM pg_extension WHERE extname = 'vector';
```

如果 `vector` 不存在，非向量功能仍可运行，但语义检索/向量化能力不可用。

---

## 本地开发部署

### 后端

```bash
cd backend
mvn spring-boot:run
```

健康检查：

```bash
curl http://localhost:8080/api/actuator/health
```

Swagger：

```bash
open http://localhost:8080/api/swagger-ui.html
```

### 前端

```bash
cd frontend
npm install
npm run dev
```

访问：

```text
http://localhost:5173
```

Vite 配置会把 `/api` 代理到 `http://localhost:8080`。

### 构建

后端：

```bash
cd backend
mvn clean package -DskipTests
```

生成：

```text
backend/target/legacygraph-api-1.0.0-SNAPSHOT.jar
```

前端：

```bash
cd frontend
npm run build
```

生成：

```text
frontend/dist/
```

---

## 生产反向代理

如果使用 `frontend` 容器，容器内 Nginx 已包含：

```nginx
location /api/ {
    proxy_pass http://backend:8080;
}

location / {
    try_files $uri $uri/ /index.html;
}
```

如果在宿主机或独立 Nginx 部署静态文件，可使用：

```nginx
server {
    listen 80;
    server_name legacygraph.example.com;

    root /opt/legacygraph/frontend/dist;
    index index.html;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 60s;
        proxy_read_timeout 300s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }

    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf)$ {
        expires 1y;
        add_header Cache-Control public;
    }

    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml text/javascript;
}
```

HTTPS 生产环境建议放在网关或 Nginx 外层，并限制 Swagger、Actuator 的公网访问。

---

## 验证清单

### 后端

```bash
curl http://localhost:8080/api/actuator/health
curl http://localhost:8080/api/v3/api-docs
```

### 前端

打开前端地址，确认：

- `/login` 可打开。
- 登录成功后进入 `/dashboard`。
- 刷新任意前端路由不 404。
- 浏览器 Network 中 API 路径是 `/api/lg/...`。

### 数据库

```sql
SELECT count(*) FROM sys_user;
SELECT count(*) FROM lg_llm_provider;
SELECT count(*) FROM lg_prompt_template;
```

### LLM Provider

```sql
SELECT provider_code, model_id, endpoint, is_default, is_active
FROM lg_llm_provider
ORDER BY id;
```

默认可用 Provider 应满足：

```text
is_default = true
is_active = true
api_config 中 api_key 已替换占位符
```

### Neo4j

```bash
nc -zv <neo4j-host> 7687
```

### Redis

```bash
redis-cli -h <redis-host> -p <redis-port> ping
```

### MinIO

```bash
mc ls legacygraph/legacy-graph
```

---

## 常见问题

### 1. `docs/sql/init.sql` 找不到

当前仓库不再使用该路径。数据库初始化由 Flyway 自动执行，脚本在：

```text
backend/src/main/resources/db/migration/
```

### 2. 后端启动时报 Flyway 错误

检查：

```sql
SELECT *
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 5;
```

不要修改已执行的迁移脚本。需要修复时新增下一个 `V{n}__*.sql`。

### 3. 前端请求路径 404

确认前端请求是 `/api/lg/...`，后端 context-path 是 `/api`，Nginx/Vite 代理没有去掉 `/api`。

### 4. 前端页面刷新 404

Nginx 必须有：

```nginx
try_files $uri $uri/ /index.html;
```

### 5. 登录后马上 401

检查：

- `JWT_SECRET` 是否稳定。
- 前端是否携带 `Authorization: Bearer <token>`。
- Redis 黑名单是否误写。
- 用户 `status` 是否为 `ACTIVE`。

### 6. LLM 调用失败

检查：

```sql
SELECT provider_code, model_id, endpoint, is_default, is_active
FROM lg_llm_provider;

SELECT task_type, status, provider_code, model_id, created_at
FROM lg_prompt_run
ORDER BY created_at DESC
LIMIT 20;
```

常见原因：

- Provider API Key 仍是占位符。
- 默认 Provider 未启用。
- 网络无法访问模型 endpoint。
- LLM 输出不是目标 DTO，可在 `lg_prompt_run.status = 'REVIEW'` 中看到。

### 7. pgvector 扩展不可用

向量能力依赖 pgvector。执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

如果没有权限，需要 DBA 处理。

### 8. Docker Compose 提示缺变量

Compose 使用 `${VAR:?message}` 对外部依赖做强校验。补齐 `deploy/.env` 后重试。

### 9. Neo4j 图为空

先确认 Neo4j 中有图谱节点和边：

```cypher
MATCH (n) RETURN count(*) AS node_count;
MATCH ()-[r]->() RETURN count(*) AS edge_count;
```

再检查后端日志中的 Neo4j 写入错误。也可在 Neo4j Browser 中按标签查看：

```cypher
MATCH (n) RETURN labels(n), count(*) ORDER BY count(*) DESC;
```

---

## 备份与恢复

### PostgreSQL

```bash
pg_dump -h <pg-host> -p 5432 -U <pg-user> -Fc legacy_graph > legacy_graph_$(date +%Y%m%d).dump
pg_restore -h <pg-host> -p 5432 -U <pg-user> -d legacy_graph --clean --if-exists legacy_graph_YYYYMMDD.dump
```

### Neo4j

```bash
neo4j-admin database dump neo4j --to-path=/backup
neo4j-admin database load neo4j --from-path=/backup --overwrite-destination=true
```

### MinIO

```bash
mc mirror legacygraph/legacy-graph ./backup/legacy-graph
```

---

## 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 2.0 | 2026-07-03 | 新增 V6-V30 迁移版本（25 个新脚本）；更新 Flyway 版本检查范围 |
| 1.2 | 2026-07-01 | 修正图谱存储描述：Neo4j 查询替换 PostgreSQL `lg_graph_node`/`lg_graph_edge` |
| 1.1 | 2026-06-30 | 按当前 Dockerfile、Compose、Flyway、前端代理和外部依赖部署方式更新 |
| 1.0 | 2026-06-27 | 初始版本 |
