# =====================================================================
# Morphia 3D Backend — Dockerfile (multi-stage build)
#
# Build stage:  maven:3.9.9-eclipse-temurin-17  (khớp với maven-wrapper)
# Runtime stage: eclipse-temurin:17-jre
#
# Cách build:
#   docker build -t morphia-3d-be:0.0.1-SNAPSHOT .
#
# Cách chạy (cần cung cấp đầy đủ env):
#   docker run -d --name morphia-3d-be \
#     -p 8080:8080 \
#     --env-file .env \
#     morphia-3d-be:0.0.1-SNAPSHOT
#
# Lưu ý: Flyway bị tắt khi khởi động (spring.flyway.enabled=false).
# Hãy chạy migration CSDL riêng biệt trước khi triển khai container này.
# =====================================================================

# ----------------------------------------------------------------
# Stage 1: Build
# ----------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy file cấu hình Maven trước để tận dụng layer cache của dependencies
COPY .mvn .mvn
COPY pom.xml ./

# Tải dependencies (offline cache) — layer này được giữ nếu pom.xml không đổi
RUN mvn dependency:go-offline -Dmaven.repo.local=/root/.m2/repository -q

# Copy source code và đóng gói (bỏ qua test vì test cần Testcontainers/CSDL)
COPY src src
RUN mvn package -DskipTests -Dmaven.repo.local=/root/.m2/repository -q

# Xác minh artifact đã sinh ra
RUN ls -la .mvn-target/*.jar

# ----------------------------------------------------------------
# Stage 2: Runtime
# ----------------------------------------------------------------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Tạo user không phải root để chạy ứng dụng (best practice bảo mật)
RUN groupadd -r appuser && useradd -r -g appuser -d /app -s /sbin/nologin appuser

# Copy artifact từ stage build
COPY --from=builder /build/.mvn-target/morphia-3d-0.0.1-SNAPSHOT.jar app.jar

# Chown để user appuser có quyền đọc file
RUN chown appuser:appuser app.jar

USER appuser

# Port mà Spring Boot lắng nghe (mặc định 8080)
EXPOSE 8080

# Biến môi trường JVM (tùy chọn, có thể override khi chạy container)
ENV JAVA_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Ho_Chi_Minh"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
