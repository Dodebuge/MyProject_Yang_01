#!/bin/sh
# Jetson 배포 (GitHub Actions self-hosted runner가 실행, 손으로 실행해도 됨):
#   테스트 -> 빌드(run.sh prepare) -> (서비스 파일이 바뀌었으면 설치) -> 서비스 재시작 -> 9080 응답 확인. 테스트·빌드가 실패하면 재시작하지 않아 기존 서버가 그대로 돕니다.
# git pull은 워크플로가 먼저 합니다 (손으로 할 때: git pull --ff-only 뒤 이 스크립트).
set -eu
DIR=/home/jetson/yang/MyProject_Yang_01
SERVICE=kb-openapi-spring
cd "$DIR"
echo "== $(git log --oneline -1)"

echo "== 테스트"
./spring-app/gradlew -q --no-daemon -p spring-app test

echo "== 빌드"
./spring-app/run.sh prepare

# 저장소의 서비스 파일(실행 경로 등)이 설치된 것과 다르면 바꿔 넣음
if ! cmp -s deploy/jetson/$SERVICE.service /etc/systemd/system/$SERVICE.service; then
  echo "== 서비스 파일 갱신"
  sudo install -m 644 deploy/jetson/$SERVICE.service /etc/systemd/system/$SERVICE.service
  sudo systemctl daemon-reload
fi

# SLO 모니터 (22888, spring-app/slo-monitor): Spring과 따로 돌아 Spring 재시작 중의 응답 없음도 잼. 실패해도 Spring 배포는 계속
# (테스트는 위 test가 slo-monitor까지 함께 돌림)
MON=kb-slo-monitor
echo "== $MON 빌드"
./spring-app/gradlew -q --no-daemon -p spring-app :slo-monitor:bootJar || echo "!! $MON 빌드 실패 (기존 jar로 계속)" >&2
if ! cmp -s deploy/jetson/$MON.service /etc/systemd/system/$MON.service; then
  echo "== $MON 서비스 파일 설치"
  { sudo install -m 644 deploy/jetson/$MON.service /etc/systemd/system/$MON.service \
    && sudo systemctl daemon-reload && sudo systemctl enable $MON; } || echo "!! $MON 설치 실패 (sudo 권한 확인)" >&2
fi
echo "== $MON 재시작"
sudo systemctl restart $MON || echo "!! $MON 재시작 실패: journalctl -u $MON -n 50" >&2

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
