#!/bin/bash
# 사용법:
#   ./update.sh test   → develop 최신 코드로 테스트 서버 업데이트
#   ./update.sh prod   → main 최신 코드로 운영 서버 업데이트
#   ./update.sh all    → 둘 다
set -e
cd "$(dirname "$0")"

update() {
  local name=$1
  echo "▶ [$name] 최신 코드 받는 중..."
  git -C "$name" pull --ff-only
  echo "▶ [$name] 새로 빌드하고 재시작하는 중... (1~3분)"
  docker compose up -d --build "$name"
  echo "✅ [$name] 업데이트 완료"
}

case "$1" in
  test) update test ;;
  prod) update prod ;;
  all)  update test; update prod ;;
  *) echo "사용법: ./update.sh test | prod | all"; exit 1 ;;
esac
docker image prune -f >/dev/null 2>&1 || true
