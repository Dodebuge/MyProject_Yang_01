#!/bin/sh
# Spring Boot 버전 실행 (Linux/macOS). 처음 한 번은 Maven과 의존성을 받느라 1~2분 걸립니다.
#   ./run.sh                  -> http://localhost:8080
#   PORT=8000 ./run.sh        -> 다른 포트
#   ./run.sh prepare          -> 빌드·CDS 준비만 하고 끝냄 (systemd 서비스 배포 전에 실행)
#   JAVA_OPTS="" ./run.sh     -> 기본 JVM 옵션(-XX:TieredStopAtLevel=1)을 끄고 실행
# .env(appKey/appSecret)는 저장소 루트에서 읽습니다 (KB_DATA_DIR로 바꿀 수 있음).
#
# 시작을 빠르게 하는 세 가지:
#   1) 소스·화면·docs가 jar보다 새로울 때만 빌드합니다 (매번 Maven을 돌리지 않음).
#   2) jar를 풀어 두고 CDS(클래스 데이터 공유) 아카이브를 한 번 만들어 재사용합니다.
#   3) -XX:TieredStopAtLevel=1: JIT를 가볍게 해서 시작을 줄입니다 (KB 응답을 기다리는 앱이라 성능 차이는 작음).
set -e
cd "$(dirname "$0")"
export KB_DATA_DIR="${KB_DATA_DIR:-$(cd .. && pwd)}"
JAR=target/kb-openapi-spring.jar
APP=target/app   # 푼 jar(kb-openapi-spring.jar + lib/)와 CDS 아카이브(app.jsa)

if [ ! -f "$JAR" ] || [ -n "$(find pom.xml src ../java-app/src ../home.html ../heatmap.html ../me.html ../docs -newer "$JAR" -print -quit)" ]; then
  ./mvnw -q -B -DskipTests package
fi

# jar가 바뀌면 다시 풀고, 앱을 한 번 띄웠다 끝내며(onRefresh) 읽은 클래스를 app.jsa로 저장합니다.
# 학습에 실패해도(예: .env 없음) 실행은 CDS 없이 계속합니다.
if [ ! -f "$APP/app.jsa" ] || [ "$JAR" -nt "$APP/app.jsa" ]; then
  rm -rf "$APP"
  java -Djarmode=tools -jar "$JAR" extract --destination "$APP"
  java -XX:ArchiveClassesAtExit="$APP/app.jsa" -Dspring.context.exit=onRefresh -Dserver.port=0 \
    -jar "$APP/kb-openapi-spring.jar" > "$APP/cds-training.log" 2>&1 \
    || echo "CDS 아카이브를 만들지 못했습니다 ($APP/cds-training.log). CDS 없이 실행합니다." >&2
fi

[ "$1" = "prepare" ] && exit 0
CDS=""
[ -f "$APP/app.jsa" ] && CDS="-XX:SharedArchiveFile=$APP/app.jsa"
# shellcheck disable=SC2086  # JAVA_OPTS는 여러 옵션을 공백으로 나눠 넘깁니다
exec java $CDS ${JAVA_OPTS--XX:TieredStopAtLevel=1} -jar "$APP/kb-openapi-spring.jar"
