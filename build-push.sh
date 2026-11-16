#!/usr/bin/env bash
# =====================================================================
# Script build & push Docker image cho Morphia 3D Backend
# Dùng cho CI/CD (GitHub Actions, GitLab CI, Jenkins...)
#
# Biến môi trường cần thiết:
#   DOCKER_REGISTRY   : ví dụ ghcr.io, docker.io, hoặc internal registry
#   DOCKER_USERNAME   : tên đăng nhập registry
#   DOCKER_PASSWORD   : mật khẩu / access token
#   IMAGE_TAG         : tag image (mặc định: 0.0.1-SNAPSHOT)
#
# Cách dùng:
#   chmod +x build-push.sh
#   ./build-push.sh
# =====================================================================
set -Eeuo pipefail

BACKEND_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$BACKEND_DIR"

REGISTRY="${DOCKER_REGISTRY:-docker.io}"
IMAGE_NAME="${IMAGE_NAME:-morphia-3d-be}"
IMAGE_TAG="${IMAGE_TAG:-0.0.1-SNAPSHOT}"
FULL_IMAGE="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"

echo ">> Đăng nhập vào registry: ${REGISTRY}"
if [[ -z "${DOCKER_USERNAME:-}" || -z "${DOCKER_PASSWORD:-}" ]]; then
    echo "Lỗi: Thiếu DOCKER_USERNAME hoặc DOCKER_PASSWORD" >&2
    exit 1
fi
echo "${DOCKER_PASSWORD}" | docker login "${REGISTRY}" -u "${DOCKER_USERNAME}" --password-stdin

echo ">> Xây dựng image: ${FULL_IMAGE}"
docker build -t "${FULL_IMAGE}" .

echo ">> Đẩy image lên registry"
docker push "${FULL_IMAGE}"

echo ">> Hoàn tất: ${FULL_IMAGE}"
