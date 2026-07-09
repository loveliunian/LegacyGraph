# 构建阶段
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 复制 pom.xml 并下载依赖（利用缓存）
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并构建
COPY src ./src
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# 复制构建好的 jar
COPY --from=builder /build/target/*.jar app.jar

# 暴露端口
EXPOSE 8080

# JVM 参数优化
# 说明：扫描/建图阶段对象分配密集，AiScanStepSupport 的并发度按 4GB 堆调优（见其注释）。
# 容器内用 MaxRAMPercentage 按 Pod/容器内存上限自适应堆大小（配 -m 4g 时约 3g 堆），
# 避免硬编码 -Xmx2g 与代码并发度假设不符导致扫描 OOM。
# 如需固定堆，可用 JAVA_OPTS 覆盖为 -Xms2g -Xmx4g。
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError"

# 启动
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
