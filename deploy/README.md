# 리눅스 서버 배포

## 방법 A. Spring Boot (권장, Tomcat·Python 설치 불필요)

```
내 PC 브라우저 ──http://서버IP:8080/──▶ java -jar kb-openapi-spring.jar (내장 Tomcat) ──▶ KB OpenAPI
```

```bash
git clone https://github.com/Dodebuge/MyProject_Yang_01.git /opt/kb_openapi_sample
cd /opt/kb_openapi_sample
vi .env && chmod 600 .env                     # KB_OPENAPI_BASE_URL, KB_OPENAPI_APP_KEY, KB_OPENAPI_APP_SECRET
sudo -u kbapp spring-app/run.sh prepare      # JDK 17 이상 필요. 빌드 + CDS 준비 (서비스와 같은 kbapp 사용자로)
sudo cp deploy/kb-openapi-spring.service /etc/systemd/system/      # WorkingDirectory, User 확인
sudo systemctl daemon-reload && sudo systemctl enable --now kb-openapi-spring
```

코드 업데이트: `git pull` 후 `sudo systemctl restart kb-openapi-spring`. 서비스가 시작할 때 `run.sh prepare`로 바뀐 것만 다시 빌드합니다.
화면이 예전 그대로 보이면 브라우저에서 Ctrl+F5로 한 번 새로고침하세요 (이전 버전은 화면에 캐시 금지 헤더가 없었습니다).

방화벽에서 8080만 열면 됩니다 (아래 "방화벽"). 접근 제한은 없습니다.

## 방법 B. Tomcat 10 + Python

```
내 PC 브라우저 ──http://서버IP:8080/kb/──▶ Tomcat 10 (kb.war, 프록시) ──http://127.0.0.1:8000──▶ dividends_web.py ──▶ KB OpenAPI
```

- Tomcat은 받은 요청을 같은 서버의 Python 서버로 그대로 넘기기만 합니다(`tomcat-proxy/`, 외부 라이브러리 없음).
- Python 서버는 `127.0.0.1`에만 열어 두고, 외부 접속은 Tomcat 포트(8080)만 받습니다.
- **접근 제한 없음**: 서버 IP를 아는 같은 네트워크의 누구나 `내정보`(잔고·배당)를 볼 수 있습니다.
  로그인이 필요해지면 `tomcat-proxy/WEB-INF/web.xml` 아래쪽 주석을 풀면 됩니다.

### 필요한 것

- Tomcat 10.1 (jakarta.servlet), JDK 11 이상 (`javac`, `jar`). Tomcat 9 이하(javax.servlet)에서는 동작하지 않습니다.
- Python 3.9 이상, `pip install -r requirements.txt`

### 1. 코드와 키 올리기

```bash
sudo mkdir -p /opt/kb_openapi_sample && sudo chown $USER /opt/kb_openapi_sample
git clone https://github.com/Dodebuge/MyProject_Yang_01.git /opt/kb_openapi_sample
cd /opt/kb_openapi_sample
python3 -m pip install -r requirements.txt
vi .env        # KB_OPENAPI_BASE_URL, KB_OPENAPI_APP_KEY, KB_OPENAPI_APP_SECRET (git에는 없음)
chmod 600 .env
```

### 2. Python 서버를 서비스로 실행

```bash
python3 dividends_web.py --no-open          # 먼저 직접 실행해 오류가 없는지 확인 (Ctrl+C)
sudo cp deploy/kb-openapi.service /etc/systemd/system/
sudo vi /etc/systemd/system/kb-openapi.service   # WorkingDirectory, User 확인
sudo systemctl daemon-reload && sudo systemctl enable --now kb-openapi
curl -s http://127.0.0.1:8000/ | head -3    # 서버 안에서만 열리는지 확인
```

### 3. Tomcat에 프록시 WAR 올리기

```bash
cd /opt/kb_openapi_sample/deploy/tomcat-proxy
CATALINA_HOME=/opt/tomcat ./build.sh        # Tomcat 설치 경로에 맞게
cp kb.war /opt/tomcat/webapps/              # 몇 초 뒤 자동 배포 → http://서버IP:8080/kb/
```

루트 주소(`http://서버IP:8080/`)로 쓰려면 기존 `webapps/ROOT`를 치우고 `ROOT.war` 이름으로 복사하세요.
페이지 안의 링크와 API 호출은 상대경로라 어느 경로에 올려도 동작합니다.

## 보유 금액 4시간 기록 (선택, A·B 공통)

`.env`에 `MYSQL_HOST`를 넣으면 서버가 한국 시간 00·04·08·12·16·20시마다 KB·토스 보유 현황을 새로 조회해
MySQL/MariaDB의 `holdings_snapshot` 테이블에 합계를 저장합니다. 내정보 '보유 금액 변화' 그래프가 이 값을 씁니다.
비워 두면 기록하지 않고 그래프는 kb.db의 하루 기록(내정보를 연 날)만 씁니다. 테이블은 서버가 처음 접속할 때 만듭니다.

MariaDB가 없으면 스크립트 하나로 설치·DB·계정 생성·`.env` 기록까지 끝낼 수 있습니다 (apt, dnf, yum, zypper).
`.env`에 이미 `MYSQL_HOST` 값이 있으면 아무것도 바꾸지 않습니다. 비밀번호는 무작위로 만들어 `.env`에만 적습니다.

```bash
sudo deploy/install-mariadb.sh                # 또는: sudo MYSQL_PASSWORD=비밀번호 deploy/install-mariadb.sh
sudo systemctl restart kb-openapi-spring
```

직접 설치하려면:

```bash
sudo apt install mariadb-server          # 또는 mysql-server
sudo mariadb <<'SQL'
CREATE DATABASE kb_openapi CHARACTER SET utf8mb4;
CREATE USER 'kb'@'localhost' IDENTIFIED BY '비밀번호';
GRANT ALL ON kb_openapi.* TO 'kb'@'localhost';
SQL
```

`.env`에 추가한 뒤 서비스를 재시작합니다 (`MYSQL_PORT` 기본 3306, `MYSQL_DATABASE` 기본 kb_openapi).

```
MYSQL_HOST=127.0.0.1
MYSQL_USER=kb
MYSQL_PASSWORD=비밀번호
```

로그에 `[기록] jdbc:mysql://127.0.0.1:3306/kb_openapi 에 4시간마다 보유 금액 기록 (다음 HH:MM)`이 나오면 켜진 것입니다.

서비스를 재시작하기 전에 DB 접속만 따로 확인할 수 있습니다. 접속, 테이블 생성, 쓰기·읽기(시험 쓰기는 되돌림), 기록 건수를 출력하고 실패하면 종료 코드 1입니다.

```bash
sudo -u kbapp spring-app/run.sh dbcheck       # Spring (방법 A)
python snapshots.py --check                   # Python (방법 B)
```
Python 서버(방법 B)는 `pip install -r requirements.txt`로 PyMySQL을 설치하세요.
Spring과 Python 서버를 함께 띄우면 둘 다 KB를 조회해 같은 시각 값을 덮어쓰므로, 기록은 한 서버에서만 켜는 것을 권합니다.

## HTTPS (선택, 방법 A·Python 서버)

거래량 페이지의 **브라우저 알림(🔔)은 https 주소나 localhost에서만** 됩니다(브라우저 보안 규칙). `.env`에 인증서·개인 키(PEM) 경로를 넣으면
같은 포트가 https로 열립니다. 상대 경로는 데이터 폴더(`.env`가 있는 곳) 기준이고, 파일이 없거나 깨졌으면 서버가 시작할 때 오류로 멈춥니다.

```
SSL_CERT=certs/cert.pem
SSL_KEY=certs/key.pem
```

`certs/`는 git에 올리지 않습니다(`.gitignore`). 개인 키는 `chmod 600`.

**인증서 고르기**

| 방법 | 브라우저 경고 | 휴대폰 브라우저 알림 | 하는 일 |
|---|---|---|---|
| Tailscale (권장, 밖에서도 접속) | 없음 | 됨 (안드로이드 크롬) | 서버·휴대폰에 Tailscale, `tailscale cert`로 진짜 인증서 |
| mkcert (집 안 네트워크) | 루트 인증서를 설치한 기기는 없음 | 루트 인증서를 설치하면 됨 | `deploy/make-cert.sh` + 기기마다 루트 인증서 설치 |
| 자체 서명 (openssl) | 있음 (계속 진행 눌러야 함) | 안 됨 (브라우저가 서비스 워커를 막음) | `deploy/make-cert.sh` |
| Let's Encrypt | 없음 | 됨 | 도메인과 80번 포트 공개가 필요 |

휴대폰은 ntfy 앱(README '시그널 알림')이 인증서와 상관없이 가장 확실합니다. 브라우저 알림은 PC 브라우저에 쓰기 좋습니다.

**make-cert.sh (mkcert 또는 자체 서명)**

```bash
cd /opt/kb_openapi_sample
sudo apt install mkcert              # 선택: 있으면 mkcert로, 없으면 openssl 자체 서명으로 만듦
sudo -u kbapp deploy/make-cert.sh 192.168.0.10 jetson.local   # 접속할 때 쓰는 주소 (서버 이름·IP는 자동으로 들어감)
sudo systemctl restart kb-openapi-spring                       # -> https://192.168.0.10:8080/
```

mkcert를 썼으면 `mkcert -CAROOT` 폴더의 `rootCA.pem`을 브라우저를 쓰는 PC·휴대폰에 설치해야 경고가 사라집니다
(안드로이드: 설정 → 보안 → 인증서 설치 → CA 인증서).

**Tailscale**

```bash
sudo tailscale cert --cert-file /opt/kb_openapi_sample/certs/cert.pem --key-file /opt/kb_openapi_sample/certs/key.pem 서버이름.tailnet이름.ts.net
sudo chown kbapp /opt/kb_openapi_sample/certs/*.pem && sudo chmod 600 /opt/kb_openapi_sample/certs/key.pem
```

`.env`에 위 `SSL_CERT`·`SSL_KEY`를 넣고 재시작하면 `https://서버이름.tailnet이름.ts.net:8080/`. 인증서는 90일마다 같은 명령으로 갱신하세요(cron 등).

**함께 바꿀 것**
- 시그널 알림의 `APP_URL`(ntfy 알림을 눌렀을 때 열 주소)도 `https://...`로 바꿉니다.
- http로 들어오면 열리지 않습니다(같은 포트를 https로만 엶). 즐겨찾기를 https로 바꾸세요.
- 방법 B(Tomcat + Python)는 Tomcat의 HTTPS 커넥터(server.xml의 `SSLHostConfig`)에 같은 인증서를 설정하세요. Python 서버는 Tomcat 뒤 127.0.0.1이라 http 그대로 둡니다.

## 방화벽 (A·B 공통)

```bash
sudo firewall-cmd --add-port=8080/tcp --permanent && sudo firewall-cmd --reload   # RHEL/Rocky 계열
sudo ufw allow 8080/tcp                                                            # Ubuntu 계열
```

8000번(Python)은 열지 마세요.

## 확인과 문제 해결

토스 자동매매 주문 로그 (주문 요청·접수·거절·재전송·취소·체결):

```bash
tail -f /opt/kb_openapi_sample/trader.log                        # 데이터 폴더의 자동매매 로그
journalctl -u kb-openapi-spring -f | grep --line-buffered 자동매매  # 서비스 로그에서 자동매매 줄만
```

| 증상 | 확인 |
|---|---|
| 화면에 "Python 서버에 연결할 수 없습니다" (502) | `systemctl status kb-openapi`, `journalctl -u kb-openapi -f` |
| 404 | `ls /opt/tomcat/webapps/kb` 로 배포 확인, 주소 끝의 `/kb/` |
| KB API 오류 | `.env` 값, 서버에서 `developer.kbsec.com:32484`로 나가는 방화벽 |
| ETF 신규 상장이 계속 "—" | `etf_snapshot.json`을 쓸 수 있는지 (서비스 User의 쓰기 권한) |

프록시 동작은 Windows의 Tomcat 10.1.60 + JDK 17(Java 11 대상 빌드)에서 확인했습니다: `/kb/`, `/kb/heatmap`, `/kb/me`, `/kb/api/*` 모두 200, Python 서버를 끄면 502와 원인 메시지.
