### 此模块为开发使用，实际使用请参考： https://gitee.com/westboy/lambda-fusion-admin

## Docker Compose 本地启动

编排包含 `lambda-fusion-startup`、MySQL、PostgreSQL/pgvector、Redis 和 MinIO，适用于本地开发与功能验证，不作为生产高可用方案。

在仓库根目录执行：

```powershell
Copy-Item lambda-fusion-startup/.env.example lambda-fusion-startup/.env
# 编辑 lambda-fusion-startup/.env，替换所有 change-me 占位值
mvn -DskipTests install
mvn -f lambda-fusion-startup/pom.xml -DskipTests package
docker compose --env-file lambda-fusion-startup/.env -f lambda-fusion-startup/docker-compose.yml up -d --build
```

应用地址为 `http://localhost:20005`，MinIO 控制台地址为 `http://localhost:9001`。查看状态与日志：

```powershell
docker compose --env-file lambda-fusion-startup/.env -f lambda-fusion-startup/docker-compose.yml ps
docker compose --env-file lambda-fusion-startup/.env -f lambda-fusion-startup/docker-compose.yml logs -f startup
```

停止编排：

```powershell
docker compose --env-file lambda-fusion-startup/.env -f lambda-fusion-startup/docker-compose.yml down
```

数据库、缓存和对象文件保存在 Docker 命名卷中；只有明确需要清空本地数据时才使用 `down -v`。

> Docker 镜像直接复制 Maven 构建出的 `*-executable.jar`。项目依赖本地安装的 `lambda-cloud-parent`、`lambda-cloud-project-parent` SNAPSHOT，因此须先按根目录 README 安装基座，再在宿主机完成上述 Maven 构建。
