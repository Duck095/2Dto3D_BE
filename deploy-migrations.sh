#!/usr/bin/env bash
# =====================================================================
# Chạy Flyway migration cho CSDL PostgreSQL trước khi khởi động backend.
#
# Cần thiết vì backend tắt Flyway khi khởi động (spring.flyway.enabled=false).
#
# Biến môi trường cần thiết (từ .env):
#   DB_URL, DB_PASSWORD
#
# Cách dùng:
#   chmod +x deploy-migrations.sh
#   ./deploy-migrations.sh
# =====================================================================
set -Eeuo pipefail

BACKEND_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$BACKEND_DIR"

if [[ -f .env ]]; then
    # shellcheck disable=SC2046
    export $(grep -v '^#' .env | xargs)
fi

if [[ -z "${DB_URL:-}" || -z "${DB_PASSWORD:-}" ]]; then
    echo "Lỗi: Thiếu DB_URL hoặc DB_PASSWORD trong .env" >&2
    exit 1
fi

echo ">> Chạy Flyway migration lên CSDL: ${DB_URL}"
./mvnw flyway:migrate \
    -Dflyway.url="${DB_URL}" \
    -Dflyway.user=postgres \
    -Dflyway.password="${DB_PASSWORD}" \
    -Dflyway.locations=filesystem:src/main/resources/db/migration \
    -Dflyway.baselineOnMigrate=false \
    -Dflyway.cleanDisabled=true

echo ">> Hoàn tất migration."
