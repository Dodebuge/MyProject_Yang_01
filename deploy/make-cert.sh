#!/bin/sh
# HTTPS 인증서 만들기. 거래량 페이지의 브라우저 알림(🔔)은 https 주소나 localhost에서만 됩니다.
#
#   ./deploy/make-cert.sh                       # 이 서버의 이름·IP(hostname -I)로
#   ./deploy/make-cert.sh jetson.local 1.2.3.4  # 접속할 때 쓰는 주소를 더 넣기
#
# mkcert가 있으면 mkcert로 만듭니다: 브라우저를 쓰는 PC·휴대폰에 mkcert 루트 인증서(mkcert -CAROOT의 rootCA.pem)를 설치하면 경고가 없습니다.
# 없으면 openssl 자체 서명 인증서: 접속할 때 브라우저 경고가 뜨고, 브라우저에 따라 알림·서비스 워커가 막힐 수 있습니다.
# 경고 없는 인증서가 필요하면 deploy/README.md 'HTTPS'의 Tailscale(tailscale cert)·Let's Encrypt를 보세요.
#
# 만드는 파일: <데이터 폴더>/certs/cert.pem, certs/key.pem (KB_DATA_DIR, 기본은 저장소 루트)
# .env에 SSL_CERT·SSL_KEY가 없으면 추가합니다. 서버를 다시 시작하면 같은 포트가 https로 열립니다.
set -e
cd "$(dirname "$0")/.."
DIR="${KB_DATA_DIR:-$(pwd)}"
ENV="$DIR/.env"
mkdir -p "$DIR/certs"
CERT="$DIR/certs/cert.pem"
KEY="$DIR/certs/key.pem"

NAMES="localhost 127.0.0.1 $(hostname) $(hostname -I 2>/dev/null || true) $*"

if command -v mkcert >/dev/null 2>&1; then
  # shellcheck disable=SC2086  # 이름마다 인자 하나
  mkcert -cert-file "$CERT" -key-file "$KEY" $NAMES
  echo "mkcert 루트 인증서: $(mkcert -CAROOT)/rootCA.pem  (브라우저를 쓰는 기기에 설치하면 경고 없음)"
else
  SAN=$(for n in $NAMES; do
    case "$n" in
      *:*) printf 'IP:%s,' "$n" ;;           # IPv6
      *[!0-9.]*) printf 'DNS:%s,' "$n" ;;    # 이름
      *) printf 'IP:%s,' "$n" ;;             # IPv4
    esac
  done | sed 's/,$//')
  openssl req -x509 -newkey rsa:2048 -nodes -days 825 -subj "/CN=kb-openapi" \
    -addext "subjectAltName=$SAN" -keyout "$KEY" -out "$CERT" 2>/dev/null
  echo "자체 서명 인증서를 만들었습니다 (mkcert가 없음). 브라우저 경고가 뜹니다. SAN: $SAN"
fi
chmod 600 "$KEY"

# .env에 경로 추가 (이미 있으면 그대로). 상대 경로는 데이터 폴더 기준
touch "$ENV"
grep -q '^SSL_CERT=' "$ENV" || echo "SSL_CERT=certs/cert.pem" >> "$ENV"
grep -q '^SSL_KEY=' "$ENV" || echo "SSL_KEY=certs/key.pem" >> "$ENV"

# sudo로 실행했으면 서비스 사용자(폴더 주인)가 읽을 수 있게
OWNER=$(stat -c '%U:%G' "$DIR" 2>/dev/null || true)
[ "$(id -u)" = 0 ] && [ -n "$OWNER" ] && chown -R "$OWNER" "$DIR/certs" "$ENV"

echo "완료: $CERT, $KEY (.env의 SSL_CERT·SSL_KEY). 서버를 다시 시작하면 https로 열립니다."
echo "  Spring 서비스: sudo systemctl restart kb-openapi-spring  ->  https://서버주소:포트/"
