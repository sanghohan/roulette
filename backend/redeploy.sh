#!/usr/bin/env bash
# 최신 소스를 내려받아 백엔드를 다시 빌드·실행합니다.
# 이 파일은 홈(~)에 복사해두고 쓰세요:  bash ~/redeploy.sh
# (소스 폴더는 매번 새로 받으므로, 데이터는 run.sh가 ~/pingpong-data 에 따로 보관합니다.)
set -e
cd "$HOME"

echo "▶ 최신 소스 내려받기..."
curl -L https://github.com/sanghohan/roulette/archive/refs/heads/main.tar.gz -o roulette.tar.gz
rm -rf roulette-main
tar xzf roulette.tar.gz

echo "▶ 빌드 & 실행..."
cd roulette-main/backend
bash run.sh
