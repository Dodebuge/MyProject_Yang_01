#!/bin/sh
# Jetson 배포 (GitHub Actions self-hosted runner가 실행, 손으로 실행해도 됨):
#   테스트 -> 빌드(run.sh prepare) -> 서비스 재시작 -> 9080 응답 확인. 테스트·빌드가 실패하면 재시작하지 않아 기존 서버가 그대로 돕니다.
# git pull은 워크플로가 먼저 합니다 (손으로 할 때: git pull --ff-only 뒤 이 스크립트).
set -eu
DIR=/home/jetson/yang/MyProject_Yang_01
SERVICE=kb-openapi-spring
cd "$DIR"
echo "== $(git log --oneline -1)"

echo "== 테스트 (java-app)"
./spring-app/mvnw -q -B -f java-app/pom.xml test

echo "== 빌드"
./spring-app/run.sh prepare

echo "== 재시작"
sudo systemctl restart "$SERVICE"

echo "== 응답 확인"
for i in $(seq 1 60); do
  if curl -fsS -o /dev/null http://127.0.0.1:9080/api/settings; then
    echo "배포 완료: $(git log --oneline -1) (${i}번째 확인)"
    exit 0
  fi
  sleep 5
done
echo "5분 안에 9080이 응답하지 않습니다. journalctl -u $SERVICE -n 100 을 확인하세요." >&2
sudo journalctl -u "$SERVICE" -n 60 --no-pager >&2 || true
exit 1
