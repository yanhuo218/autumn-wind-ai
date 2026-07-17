# 本地基础设施

## 范围

本地开发环境通过 Docker Compose 提供 PostgreSQL、RabbitMQ、Redis 和 MinIO。它们仅用于开发与集成测试，不包含生产环境的高可用、备份、证书和 Secret 管理配置。

所有宿主机端口只绑定到 `127.0.0.1`。数据库、消息队列、缓存和对象存储不得直接暴露到公网。

## 首次启动

1. 创建本地环境文件：

   ```powershell
   Copy-Item infra/.env.example infra/.env
   ```

2. 修改 `infra/.env` 中所有 `YOUR_*_HERE` 占位值。该文件已被 Git 忽略，不得提交真实密码。

3. 校验配置：

   ```powershell
   pwsh -NoProfile -File scripts/verify-infrastructure.ps1
   ```

4. 启动基础设施：

   ```powershell
   docker compose --env-file infra/.env -f infra/compose.yaml up -d
   ```

5. 查看健康状态：

   ```powershell
   docker compose --env-file infra/.env -f infra/compose.yaml ps
   ```

## 本地端口

| 服务 | 默认地址 | 用途 |
| --- | --- | --- |
| PostgreSQL | `127.0.0.1:5432` | 服务独立 schema 和迁移历史 |
| RabbitMQ | `127.0.0.1:5672` | 领域事件与后台任务 |
| RabbitMQ Management | `http://127.0.0.1:15672` | 本地队列诊断 |
| Redis | `127.0.0.1:6379` | 限流、短期流状态和缓存 |
| MinIO API | `http://127.0.0.1:9000` | S3-compatible 对象存储 |
| MinIO Console | `http://127.0.0.1:9001` | 本地对象存储管理 |

端口可在 `infra/.env` 中修改。服务连接信息后续由各服务自己的配置边界持有。

## 停止与清理

停止容器但保留数据：

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml down
```

删除命名卷会永久清除本地数据，只能在明确不再需要这些数据时执行：

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml down --volumes
```

生产环境不得直接复用示例密码、端口暴露方式或单节点部署参数。
