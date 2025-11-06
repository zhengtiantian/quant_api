# ===== 构建阶段 =====
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

# 先复制 pom.xml 以缓存依赖
COPY pom.xml .

# 预下载依赖（提高构建速度）
RUN mvn dependency:go-offline -B

# 再复制源代码
COPY src ./src

# 构建 jar 包（跳过测试）
RUN mvn -B clean package -DskipTests

# ===== 运行阶段 =====
FROM eclipse-temurin:21-jre

WORKDIR /app

# 拷贝构建好的 jar
COPY --from=builder /app/target/*.jar app.jar

# 暴露端口
EXPOSE 8081

# 健康检查（可选）
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s CMD curl -f http://localhost:8080/api/dbtest || exit 1

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]