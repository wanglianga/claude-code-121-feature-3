# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先拷贝 pom 预热依赖缓存
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# 编译打包
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /build/target/cold-chain-delivery-1.0.0.jar app.jar

EXPOSE 8080

# 通过环境变量覆盖 Redis 地址（compose 中为 redis）
ENV REDIS_HOST=redis \
    REDIS_PORT=6379 \
    REDIS_DB=0 \
    SERVER_PORT=8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
