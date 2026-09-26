#!/bin/sh
# Spring Boot 버전 실행 (Linux/macOS). 처음 한 번은 Maven과 의존성을 받느라 1~2분 걸립니다.
#   ./run.sh                  -> http://localhost:8080
#   PORT=8000 ./run.sh        -> 다른 포트
# .env(appKey/appSecret)는 저장소 루트에서 읽습니다 (KB_DATA_DIR로 바꿀 수 있음).
set -e
cd "$(dirname "$0")"
export KB_DATA_DIR="${KB_DATA_DIR:-$(cd .. && pwd)}"
./mvnw -q -B -DskipTests package
exec java -jar target/kb-openapi-spring.jar
