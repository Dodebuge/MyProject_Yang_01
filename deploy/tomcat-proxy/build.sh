#!/bin/sh
# Tomcat 10.1용 프록시 WAR(kb.war)를 만듭니다. 외부 라이브러리 없이 JDK와 Tomcat의 servlet-api.jar만 씁니다.
#
#   CATALINA_HOME=/opt/tomcat ./build.sh
#   cp kb.war $CATALINA_HOME/webapps/      # -> http://서버IP:8080/kb/
#   (루트 주소로 쓰려면 ROOT.war 이름으로 복사: http://서버IP:8080/)
set -e
cd "$(dirname "$0")"
: "${CATALINA_HOME:?CATALINA_HOME(Tomcat 설치 경로)을 지정하세요. 예: CATALINA_HOME=/opt/tomcat ./build.sh}"

rm -rf build kb.war
mkdir -p build/WEB-INF/classes
# Tomcat 10.1은 Java 11 이상에서 돌아가므로 Java 11 바이트코드로 만듭니다 (jakarta.servlet).
javac --release 11 -nowarn -encoding UTF-8 \
  -cp "$CATALINA_HOME/lib/servlet-api.jar" \
  -d build/WEB-INF/classes src/kb/proxy/ProxyServlet.java
cp WEB-INF/web.xml build/WEB-INF/
(cd build && jar cf ../kb.war .)
rm -rf build
echo "kb.war 생성 완료"
