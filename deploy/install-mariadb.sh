#!/bin/sh
# 보유 금액 4시간 기록용 MariaDB 설치·설정 (리눅스 서버, root로 실행)
#   sudo deploy/install-mariadb.sh                 -> 저장소 루트의 .env에 MYSQL_* 추가
#   sudo deploy/install-mariadb.sh /경로/.env      -> 다른 .env
#   sudo MYSQL_PASSWORD=비밀번호 deploy/install-mariadb.sh   -> 비밀번호 지정 (없으면 무작위로 만듦)
#
# 1) MariaDB/MySQL이 없으면 패키지 관리자(apt, dnf, yum, zypper)로 mariadb-server를 설치하고 켭니다.
# 2) 데이터베이스(kb_openapi)와 계정(kb@localhost)을 만듭니다. 이미 있으면 그대로 두고 비밀번호만 맞춥니다.
# 3) .env에 MYSQL_HOST·PORT·DATABASE·USER·PASSWORD를 적습니다.
# .env에 MYSQL_HOST가 이미 값이 있으면 아무것도 바꾸지 않고 끝냅니다 (이미 설정된 DB를 덮어쓰지 않게).
set -e

ENV_FILE="${1:-$(cd "$(dirname "$0")/.." && pwd)/.env}"
DB="${MYSQL_DATABASE:-kb_openapi}"
DB_USER="${MYSQL_USER:-kb}"

if [ "$(id -u)" -ne 0 ]; then
  echo "root 권한이 필요합니다: sudo $0 $*" >&2
  exit 1
fi
if [ -f "$ENV_FILE" ] && grep -Eq '^MYSQL_HOST=.+' "$ENV_FILE"; then
  echo "$ENV_FILE 에 MYSQL_HOST가 이미 있습니다. 바꾸지 않고 끝냅니다."
  exit 0
fi
case "$DB$DB_USER" in
  *[!A-Za-z0-9_]*) echo "MYSQL_DATABASE·MYSQL_USER는 영문·숫자·_만 쓸 수 있습니다." >&2; exit 1 ;;
esac

# ---------------------------------------------------------------- 1. 설치
if command -v mariadb >/dev/null 2>&1 || command -v mysql >/dev/null 2>&1; then
  echo "[1/3] MariaDB/MySQL 클라이언트가 이미 있습니다. 설치를 건너뜁니다."
else
  echo "[1/3] mariadb-server 설치"
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q mariadb-server
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y mariadb-server
  elif command -v yum >/dev/null 2>&1; then
    yum install -y mariadb-server
  elif command -v zypper >/dev/null 2>&1; then
    zypper --non-interactive install mariadb
  else
    echo "지원하는 패키지 관리자(apt, dnf, yum, zypper)가 없습니다. MariaDB를 직접 설치한 뒤 다시 실행하세요." >&2
    exit 1
  fi
fi

# 서비스 이름은 배포판마다 다름 (mariadb / mysql / mysqld)
systemctl enable --now mariadb 2>/dev/null || systemctl enable --now mysql 2>/dev/null || systemctl enable --now mysqld

SQL_CLIENT=$(command -v mariadb || command -v mysql)

# ---------------------------------------------------------------- 2. 데이터베이스·계정
if [ -z "$MYSQL_PASSWORD" ]; then
  MYSQL_PASSWORD=$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')  # 36자 16진수 (따옴표 걱정 없음)
fi
case "$MYSQL_PASSWORD" in
  *\'*|*\\*) echo "MYSQL_PASSWORD에 ' 또는 \\ 는 쓸 수 없습니다." >&2; exit 1 ;;
esac

echo "[2/3] 데이터베이스 $DB, 계정 $DB_USER@localhost"
# 새로 설치한 MariaDB의 root는 비밀번호 없이 unix 소켓으로 접속됩니다 (sudo로 실행했으니 root)
"$SQL_CLIENT" -u root <<SQL
CREATE DATABASE IF NOT EXISTS \`$DB\` CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS '$DB_USER'@'localhost' IDENTIFIED BY '$MYSQL_PASSWORD';
ALTER USER '$DB_USER'@'localhost' IDENTIFIED BY '$MYSQL_PASSWORD';
GRANT ALL PRIVILEGES ON \`$DB\`.* TO '$DB_USER'@'localhost';
FLUSH PRIVILEGES;
SQL

# ---------------------------------------------------------------- 3. .env
echo "[3/3] $ENV_FILE 에 MYSQL_* 추가"
if [ ! -f "$ENV_FILE" ]; then
  touch "$ENV_FILE"
  chown "$(stat -c %U:%G "$(dirname "$ENV_FILE")")" "$ENV_FILE"  # 서비스 사용자(폴더 주인)가 읽을 수 있게
fi
chmod 600 "$ENV_FILE"
sed -i '/^MYSQL_\(HOST\|PORT\|DATABASE\|USER\|PASSWORD\)=/d' "$ENV_FILE"  # .env.example에서 온 빈 값 정리
[ -z "$(tail -c 1 "$ENV_FILE")" ] || echo >> "$ENV_FILE"                    # 마지막 줄 바꿈 보장
cat >> "$ENV_FILE" <<EOF
# 보유 금액 4시간 기록 (deploy/install-mariadb.sh)
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DATABASE=$DB
MYSQL_USER=$DB_USER
MYSQL_PASSWORD=$MYSQL_PASSWORD
EOF

# 새 계정으로 접속되는지 확인 (TCP, 서버가 쓰는 방식 그대로)
MYSQL_PWD="$MYSQL_PASSWORD" "$SQL_CLIENT" -h 127.0.0.1 -P 3306 -u "$DB_USER" "$DB" -e "SELECT 1" >/dev/null  # 비밀번호를 명령줄(ps)에 노출하지 않음
echo
echo "완료. 서비스를 재시작하면 기록이 켜집니다:"
echo "  sudo systemctl restart kb-openapi-spring"
echo "  journalctl -u kb-openapi-spring | grep 기록     # '4시간마다 보유 금액 기록 (다음 HH:MM)' 확인"
