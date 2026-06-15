#!/usr/bin/env bash
# 변경사항을 GitHub에 올리는 스크립트 (개발 PC에서 사용)
# 사용법:
#   ./push.sh "커밋 메시지"
#   ./push.sh            # 메시지 생략 시 날짜로 자동 작성
set -e
cd "$(dirname "$0")"

MSG="${1:-update $(date '+%Y-%m-%d %H:%M')}"

echo "▶ 스테이징 & 커밋..."
git add -A
if git diff --cached --quiet; then
  echo "  (커밋할 변경사항이 없어요)"
else
  git commit -m "$MSG"
fi

echo "▶ 원격 변경 가져오기 (rebase)..."
git pull --rebase origin main

echo "▶ 푸시..."
git push origin main

echo "✅ 완료!"
