# 리눅스 서버 배포 (Tomcat 10 + Python)

```
내 PC 브라우저 ──http://서버IP:8080/kb/──▶ Tomcat 10 (kb.war, 프록시) ──http://127.0.0.1:8000──▶ dividends_web.py ──▶ KB OpenAPI
```

- Tomcat은 받은 요청을 같은 서버의 Python 서버로 그대로 넘기기만 합니다(`tomcat-proxy/`, 외부 라이브러리 없음).
- Python 서버는 `127.0.0.1`에만 열어 두고, 외부 접속은 Tomcat 포트(8080)만 받습니다.
- **접근 제한 없음**: 서버 IP를 아는 같은 네트워크의 누구나 `내정보`(잔고·배당)를 볼 수 있습니다.
  로그인이 필요해지면 `tomcat-proxy/WEB-INF/web.xml` 아래쪽 주석을 풀면 됩니다.

## 필요한 것

- Tomcat 10.1 (jakarta.servlet), JDK 11 이상 (`javac`, `jar`). Tomcat 9 이하(javax.servlet)에서는 동작하지 않습니다.
- Python 3.9 이상, `pip install -r requirements.txt`

## 1. 코드와 키 올리기

```bash
sudo mkdir -p /opt/kb_openapi_sample && sudo chown $USER /opt/kb_openapi_sample
git clone https://github.com/Dodebuge/MyProject_Yang_01.git /opt/kb_openapi_sample
cd /opt/kb_openapi_sample
python3 -m pip install -r requirements.txt
vi .env        # KB_OPENAPI_BASE_URL, KB_OPENAPI_APP_KEY, KB_OPENAPI_APP_SECRET (git에는 없음)
chmod 600 .env
```

## 2. Python 서버를 서비스로 실행

```bash
python3 dividends_web.py --no-open          # 먼저 직접 실행해 오류가 없는지 확인 (Ctrl+C)
sudo cp deploy/kb-openapi.service /etc/systemd/system/
sudo vi /etc/systemd/system/kb-openapi.service   # WorkingDirectory, User 확인
sudo systemctl daemon-reload && sudo systemctl enable --now kb-openapi
curl -s http://127.0.0.1:8000/ | head -3    # 서버 안에서만 열리는지 확인
```

## 3. Tomcat에 프록시 WAR 올리기

```bash
cd /opt/kb_openapi_sample/deploy/tomcat-proxy
CATALINA_HOME=/opt/tomcat ./build.sh        # Tomcat 설치 경로에 맞게
cp kb.war /opt/tomcat/webapps/              # 몇 초 뒤 자동 배포 → http://서버IP:8080/kb/
```

루트 주소(`http://서버IP:8080/`)로 쓰려면 기존 `webapps/ROOT`를 치우고 `ROOT.war` 이름으로 복사하세요.
페이지 안의 링크와 API 호출은 상대경로라 어느 경로에 올려도 동작합니다.

## 4. 방화벽

```bash
sudo firewall-cmd --add-port=8080/tcp --permanent && sudo firewall-cmd --reload   # RHEL/Rocky 계열
sudo ufw allow 8080/tcp                                                            # Ubuntu 계열
```

8000번(Python)은 열지 마세요.

## 확인과 문제 해결

| 증상 | 확인 |
|---|---|
| 화면에 "Python 서버에 연결할 수 없습니다" (502) | `systemctl status kb-openapi`, `journalctl -u kb-openapi -f` |
| 404 | `ls /opt/tomcat/webapps/kb` 로 배포 확인, 주소 끝의 `/kb/` |
| KB API 오류 | `.env` 값, 서버에서 `developer.kbsec.com:32484`로 나가는 방화벽 |
| ETF 신규 상장이 계속 "—" | `etf_snapshot.json`을 쓸 수 있는지 (서비스 User의 쓰기 권한) |

프록시 동작은 Windows의 Tomcat 10.1.60 + JDK 17(Java 11 대상 빌드)에서 확인했습니다: `/kb/`, `/kb/heatmap`, `/kb/me`, `/kb/api/*` 모두 200, Python 서버를 끄면 502와 원인 메시지.
