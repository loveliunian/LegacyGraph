# Graphify 部署与回滚文档

## 概述

本文档描述 Graphify 集成的部署流程、指标告警配置、容量规划和回滚方案。

## 启用步骤

### 1. 环境检查

```bash
# 检查 Graphify CLI
graphify --version

# 检查数据库连接
psql -h <host> -U <user> -d legacy_graph -c "SELECT 1"

# 检查 Neo4j 连接
cypher-shell -a bolt://<host>:7687 -u neo4j -p <password> "RETURN 1"

# 检查 Redis 连接
redis-cli -h <host> -p 6379 ping
```

### 2. 配置环境变量

```bash
# .env.local
GRAPHIFY_ENABLED=true
GRAPHIFY_COMMAND=/opt/graphify/bin/graphify
GRAPHIFY_MAX_ATTEMPTS=3
GRAPHIFY_REVIEW_ENABLED=true
GRAPHIFY_AUTO_PASS_THRESHOLD=0.9

# 数据库配置
PG_HOST=118.145.225.100
PG_PORT=5432
PG_DATABASE=legacy_graph
PG_USER=mumuai
PG_PASSWORD=<password>

# Neo4j 配置
NEO4J_URI=bolt://118.145.225.100:7687
NEO4J_USER=neo4j
NEO4J_PASSWORD=<password>
```

### 3. 启动服务

```bash
# 启动后端
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=env.local

# 或使用 Docker
docker-compose up -d legacygraph-api
```

### 4. 验证部署

```bash
# 检查健康状态
curl http://localhost:8080/actuator/health

# 测试 Graphify 端点
curl -X POST http://localhost:8080/api/lg/projects/test-project/graphify/jobs \
  -H "Content-Type: application/json" \
  -d '{"projectRoot": "/path/to/project"}'
```

## 禁用步骤

### 1. 禁用 Graphify 功能

```bash
# 修改环境变量
GRAPHIFY_ENABLED=false

# 重启服务
docker-compose restart legacygraph-api
```

### 2. 保留数据

- 数据库中的图谱数据保留
- 审核记录保留
- 原始 graph.json 文件保留

### 3. 清理资源（可选）

```bash
# 清理未完成的导入作业
DELETE FROM lg_graphify_import_job WHERE status IN ('QUEUED', 'RUNNING');

# 清理过期的审核队列
DELETE FROM lg_graphify_review_queue WHERE created_at < NOW() - INTERVAL '30 days';
```

## 指标告警

### 关键指标

| 指标 | 告警阈值 | 告警级别 | 处理方式 |
|------|---------|:---:|---------|
| `legacygraph.graphify.import.duration` | > 30 min | WARNING | 检查项目规模，优化扫描参数 |
| `legacygraph.graphify.import.failures` | > 3/小时 | CRITICAL | 检查 Graphify CLI，查看日志 |
| `legacygraph.graphify.review.queue.size` | > 1000 | WARNING | 增加审核员，优化规则 |
| `legacygraph.graphify.query.latency.p95` | > 2s | WARNING | 优化查询，增加缓存 |
| `legacygraph.graphify.query.error_rate` | > 1% | CRITICAL | 检查数据库连接，查看日志 |

### Prometheus 配置

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'legacygraph'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']

# 告警规则
groups:
  - name: graphify
    rules:
      - alert: GraphifyImportFailure
        expr: rate(legacygraph_graphify_import_failures_total[1h]) > 3
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Graphify 导入失败率过高"
```

## 容量规划

### 存储容量

| 数据类型 | 估算公式 | 示例 (100K LOC) |
|---------|---------|----------------|
| 节点数据 | ~100 bytes/节点 | ~10 MB |
| 边数据 | ~200 bytes/边 | ~20 MB |
| 审核记录 | ~500 bytes/条 | ~5 MB |
| graph.json | ~500 bytes/节点 | ~50 MB |
| **总计** | - | **~85 MB** |

### 计算资源

| 组件 | 最小配置 | 推荐配置 | 高负载配置 |
|------|---------|---------|-----------|
| API 服务 | 2C 4G | 4C 8G | 8C 16G |
| PostgreSQL | 2C 4G | 4C 8G | 8C 16G |
| Neo4j | 4C 8G | 8C 16G | 16C 32G |
| Redis | 1C 2G | 2C 4G | 4C 8G |

### 并发能力

| 场景 | 并发数 | 响应时间 |
|------|:---:|:---:|
| 查询接口 | 100 QPS | P95 < 2s |
| 导入作业 | 5 并发 | - |
| 审核操作 | 50 QPS | P95 < 500ms |

## 回滚流程

### 场景 1: 导入作业失败

```bash
# 1. 取消失败的作业
curl -X POST http://localhost:8080/api/lg/projects/{projectId}/graphify/jobs/{jobId}/cancel

# 2. 查看失败原因
curl http://localhost:8080/api/lg/projects/{projectId}/graphify/jobs/{jobId}

# 3. 修复问题后重试
curl -X POST http://localhost:8080/api/lg/projects/{projectId}/graphify/jobs/{jobId}/retry
```

### 场景 2: 数据质量问题

```bash
# 1. 回滚特定导入作业
curl -X POST http://localhost:8080/api/lg/projects/{projectId}/graphify/jobs/{jobId}/rollback

# 2. 验证回滚结果
curl http://localhost:8080/api/lg/projects/{projectId}/graphify/stats
```

### 场景 3: 系统级故障

```bash
# 1. 禁用 Graphify
GRAPHIFY_ENABLED=false
docker-compose restart legacygraph-api

# 2. 备份当前数据
pg_dump -h <host> -U <user> legacy_graph > backup_$(date +%Y%m%d).sql

# 3. 回滚到上一个稳定版本
git checkout <stable-commit>
docker-compose up -d --build legacygraph-api

# 4. 恢复数据（如需要）
psql -h <host> -U <user> legacy_graph < backup_$(date +%Y%m%d).sql
```

### 场景 4: 完全回滚

```bash
# 1. 禁用所有 Graphify 功能
GRAPHIFY_ENABLED=false

# 2. 清理 Graphify 数据（谨慎操作）
psql -h <host> -U <user> legacy_graph << EOF
DROP TABLE IF EXISTS lg_graphify_import_job CASCADE;
DROP TABLE IF EXISTS lg_graphify_review_queue CASCADE;
DROP TABLE IF EXISTS lg_graphify_audit_log CASCADE;
EOF

# 3. 清理 Neo4j 数据
cypher-shell -a bolt://<host>:7687 -u neo4j -p <password> << EOF
MATCH (n:Graphify) DETACH DELETE n;
EOF

# 4. 删除 graph.json 文件
rm -rf /opt/legacygraph/graphify-out/

# 5. 重启服务
docker-compose restart legacygraph-api
```

## 备份策略

### 数据库备份

```bash
# 每日全量备份
pg_dump -h <host> -U <user> legacy_graph | gzip > /backup/legacygraph_$(date +%Y%m%d).sql.gz

# 每小时增量备份（使用 WAL 归档）
archive_command = 'cp %p /backup/wal/%f'
```

### Neo4j 备份

```bash
# 使用 Neo4j Enterprise 备份工具
neo4j-backup --from=bolt://localhost:7687 --backup-dir=/backup/neo4j
```

### 文件备份

```bash
# 备份 graph.json 文件
rsync -avz /opt/legacygraph/graphify-out/ /backup/graphify-out/
```

## 监控仪表板

### Grafana 仪表板

- **概览**: 系统健康状态、关键指标
- **导入监控**: 导入作业状态、耗时、成功率
- **审核监控**: 审核队列大小、审核效率
- **查询监控**: 查询延迟、错误率、缓存命中率
- **资源监控**: CPU、内存、磁盘、网络

## 故障排查

### 常见问题

| 问题 | 可能原因 | 排查步骤 |
|------|---------|---------|
| 导入作业一直 QUEUED | Scheduler 未运行 | 检查日志，确认 `@Scheduled` 生效 |
| 导入失败 | Graphify CLI 错误 | 查看 `errorMessage`，检查 CLI 版本 |
| 审核队列积压 | 审核员不足 | 增加审核员，优化规则 |
| 查询超时 | 数据库慢查询 | 检查索引，优化查询 |
| 权限错误 | 角色配置错误 | 检查用户角色分配 |

### 日志位置

```bash
# 应用日志
tail -f /var/log/legacygraph/application.log

# 导入作业日志
tail -f /var/log/legacygraph/graphify-import.log

# 审核日志
tail -f /var/log/legacygraph/graphify-review.log
```

## 应急预案

### P0 级故障（系统不可用）

1. 立即禁用 Graphify
2. 通知相关团队
3. 启动故障排查
4. 修复后逐步恢复

### P1 级故障（功能异常）

1. 评估影响范围
2. 禁用受影响功能
3. 安排紧急修复
4. 修复后验证

### P2 级故障（性能问题）

1. 监控问题趋势
2. 优化配置或代码
3. 安排常规修复
