#!/usr/bin/env bash
# 탁구 백엔드 빌드 & 실행 스크립트
# 사용법: bash run.sh
set -e
cd "$(dirname "$0")"

IMAGE=pingpong-api
NAME=pingpong-api
# H2 데이터는 소스 폴더 밖(홈)에 저장해서 재배포해도 안 지워지게 함
DATA_DIR="${DATA_DIR:-$HOME/pingpong-data}"
ORIGINS="${APP_CORS_ALLOWED_ORIGINS:-https://www.donjiral.net,https://donjiral.net}"

# ===== S3 이미지 업로드 설정 =====
S3_ENABLED="${S3_ENABLED:-true}"
S3_BUCKET="${S3_BUCKET:-s3-donjiral-uploads}"
S3_REGION="${S3_REGION:-ap-southeast-2}"

mkdir -p "$DATA_DIR"

echo "▶ 이미지 빌드 (몇 분 걸릴 수 있어요)..."
docker build -t "$IMAGE" .

echo "▶ 기존 컨테이너 정리..."
docker rm -f "$NAME" 2>/dev/null || true

echo "▶ 컨테이너 실행..."
docker run -d --name "$NAME" --restart unless-stopped \
  -p 8080:8080 \
  -v "$DATA_DIR:/app/data" \
  -e APP_CORS_ALLOWED_ORIGINS="$ORIGINS" \
  -e S3_ENABLED="$S3_ENABLED" \
  -e S3_BUCKET="$S3_BUCKET" \
  -e S3_REGION="$S3_REGION" \
  "$IMAGE"

echo ""
echo "✅ 실행 완료!"
echo "   데이터 위치 : $DATA_DIR"
echo "   로그 보기   : docker logs -f $NAME"
echo "   헬스 체크   : curl http://localhost:8080/api/health"
