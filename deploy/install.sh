#!/bin/bash
# ─────────────────────────────────────────────────────────────
#  블로그 도우미 홈서버 자동 설치 (Rocky Linux 8 + 기존 Apache)
#
#  처음 설치:   bash /opt/blog/test/deploy/install.sh
#  인증서만 다시: bash /opt/blog/test/deploy/install.sh cert
#
#  하는 일: 코드 받기 → 설정(.env) 만들기 → 운영/테스트 실행
#          → Apache 주소 연결(기존 laravel 사이트 유지) → 방화벽 → HTTPS 인증서
# ─────────────────────────────────────────────────────────────
set -euo pipefail

BASE=/opt/blog
REPO=https://github.com/ldw9571/blog-assist-program.git
APACHE_CONF=/etc/httpd/conf.d/zz-blog.conf
MODE=${1:-all}

step() { echo; echo "━━━━ $* ━━━━"; }
ok()   { echo "  ✅ $*"; }
warn() { echo "  ⚠️  $*"; }
die()  { echo; echo "  ❌ $*"; exit 1; }

[ "$(id -u)" = 0 ] || die "root 계정으로 실행해 주세요."
command -v docker >/dev/null || die "Docker가 없어요. Docker를 먼저 설치해 주세요."
docker compose version >/dev/null 2>&1 || die "docker compose 가 없어요 (docker-compose-plugin 설치 필요)."
command -v httpd >/dev/null || die "Apache(httpd)가 없어요. 이 스크립트는 Apache가 있는 서버용이에요."

mkdir -p "$BASE"
cd "$BASE"

# ─────────────────────────────────────────
# 1. 설정값 (.env) — 처음 한 번만 물어봄
# ─────────────────────────────────────────
step "1. 설정"
if [ ! -f .env ]; then
  echo "  DuckDNS 에서 만든 주소 이름을 입력하세요. (.duckdns.org 는 빼고 / 붙여도 괜찮아요)"
  read -erp "  · 운영 주소 이름  (예: rentjblog)      : " PSUB
  read -erp "  · 테스트 주소 이름 (예: rentjblog-test) : " TSUB
  read -rsp "  · DuckDNS 토큰 (입력해도 화면에 안 보여요): " TOKEN; echo
  read -erp "  · 인증서 만료 알림 받을 이메일          : " EMAIL
  PSUB=${PSUB%.duckdns.org}; TSUB=${TSUB%.duckdns.org}
  PSUB=$(echo "$PSUB" | tr -d ' ' | tr 'A-Z' 'a-z'); TSUB=$(echo "$TSUB" | tr -d ' ' | tr 'A-Z' 'a-z')
  TOKEN=$(echo "$TOKEN" | tr -d ' ')
  [[ "$PSUB" =~ ^[a-z0-9-]+$ && "$TSUB" =~ ^[a-z0-9-]+$ ]] || die "주소 이름은 영문 소문자·숫자·- 만 쓸 수 있어요."
  [ -n "$TOKEN" ] || die "토큰이 비어 있어요."
  [[ "$EMAIL" == *@* ]] || die "이메일 형식이 아니에요."

  echo "  · DuckDNS 확인 중..."
  RES=$(curl -fsS "https://www.duckdns.org/update?domains=${PSUB},${TSUB}&token=${TOKEN}&ip=" || echo "ERR")
  [ "$RES" = "OK" ] || die "DuckDNS 가 거절했어요 ($RES). 주소 이름 2개와 토큰을 다시 확인해 주세요."
  ok "DuckDNS 주소가 이 집 IP로 연결됐어요."

  pw() { openssl rand -base64 18 | tr -d '/+=' | cut -c1-14; }
  umask 077
  cat > .env <<EOF
PROD_DOMAIN=${PSUB}.duckdns.org
TEST_DOMAIN=${TSUB}.duckdns.org
DUCKDNS_SUBDOMAINS=${PSUB},${TSUB}
DUCKDNS_TOKEN=${TOKEN}
LETSENCRYPT_EMAIL=${EMAIL}
PROD_ADMIN_PASSWORD=$(pw)
TEST_ADMIN_PASSWORD=$(pw)
PROD_APP_SECRET=$(openssl rand -base64 32)
TEST_APP_SECRET=$(openssl rand -base64 32)
EOF
  umask 022
  ok "설정 파일을 만들었어요: $BASE/.env (관리자 비밀번호·암호화 키 자동 생성)"
else
  ok "기존 설정 파일 사용: $BASE/.env"
fi
set -a; . ./.env; set +a

# ─────────────────────────────────────────
# 2. 코드 받기 (운영=main, 테스트=develop)
# ─────────────────────────────────────────
if [ "$MODE" = "all" ]; then
  step "2. 코드 받기"
  for pair in "test:develop" "prod:main"; do
    dir=${pair%%:*}; br=${pair##*:}
    if [ -d "$dir/.git" ]; then
      git -C "$dir" pull --ff-only -q && ok "$dir ($br) 최신으로 업데이트"
    else
      git clone -q -b "$br" "$REPO" "$dir" && ok "$dir ($br) 받기 완료"
    fi
  done
  cp test/deploy/{docker-compose.yml,Caddyfile,update.sh,backup.sh} .
  chmod +x update.sh backup.sh

# ─────────────────────────────────────────
# 3. 프로그램 실행
# ─────────────────────────────────────────
  step "3. 운영·테스트 서버 실행 (처음엔 빌드로 3~5분 걸려요)"
  # 도커 이미지 저장소가 가끔 '요청이 많다(toomanyrequests)'며 거절함 → 하나씩, 여러 번 재시도
  for img in eclipse-temurin:21-jdk eclipse-temurin:21-jre lscr.io/linuxserver/duckdns:latest; do
    for i in 1 2 3 4 5 6; do
      if docker pull -q "$img" >/dev/null 2>&1; then ok "이미지 받기: $img"; break; fi
      [ "$i" = 6 ] && die "이미지($img)를 받지 못했어요. 잠시 후 같은 명령으로 다시 실행해 주세요."
      echo "  · $img 받기 재시도 ($i/5)... 15초 대기"; sleep 15
    done
  done
  ok=0
  for i in 1 2 3; do
    if docker compose up -d --build prod test duckdns; then ok=1; break; fi
    echo "  · 빌드 재시도 ($i/2)... 20초 대기"; sleep 20
  done
  [ "$ok" = 1 ] || die "빌드에 실패했어요. 위 오류 화면을 보내주세요." 
  echo "  · 켜지는 중..."
  for port in 8081 8082; do
    for i in $(seq 1 60); do
      if curl -fs -o /dev/null "http://127.0.0.1:${port}/login"; then ok "127.0.0.1:${port} 응답 확인"; break; fi
      sleep 3
      [ "$i" = 60 ] && die "127.0.0.1:${port} 가 켜지지 않아요. 'docker compose logs' 결과를 보내주세요."
    done
  done

# ─────────────────────────────────────────
# 4. Apache 주소 연결 (기존 laravel 사이트 유지)
# ─────────────────────────────────────────
  step "4. Apache 연결"
  BACKUP=""
  [ -f "$APACHE_CONF" ] && { BACKUP="${APACHE_CONF}.bak.$(date +%s)"; cp "$APACHE_CONF" "$BACKUP"; }
  if [ ! -f "${APACHE_CONF%.conf}-le-ssl.conf" ] || [ ! -f "$APACHE_CONF" ]; then
    sed -e "s/PROD_DOMAIN/${PROD_DOMAIN}/" -e "s/TEST_DOMAIN/${TEST_DOMAIN}/" test/deploy/apache/zz-blog.conf > "$APACHE_CONF"
  else
    ok "인증서 설정이 이미 있어서 Apache 연결 파일은 그대로 둘게요."
  fi
  if command -v getenforce >/dev/null && [ "$(getenforce)" = "Enforcing" ]; then
    echo "  · SELinux 허용 설정 중... (30초쯤 걸릴 수 있어요)"
    setsebool -P httpd_can_network_connect 1
    ok "SELinux: Apache → 프로그램 연결 허용"
  fi
  if ! httpd -t 2>/tmp/httpd-test.log; then
    cat /tmp/httpd-test.log
    if [ -n "$BACKUP" ]; then cp "$BACKUP" "$APACHE_CONF"; else rm -f "$APACHE_CONF"; fi
    die "Apache 설정 검사 실패 → 원래대로 되돌렸어요. 위 메시지를 보내주세요."
  fi
  systemctl reload httpd
  DEFAULT=$(httpd -S 2>/dev/null | grep -m1 -E '^\*:80|default server' || true)
  ok "Apache 적용 완료. 기본 사이트: ${DEFAULT}"

# ─────────────────────────────────────────
# 5. 방화벽
# ─────────────────────────────────────────
  step "5. 방화벽"
  if systemctl is-active -q firewalld; then
    firewall-cmd -q --permanent --add-service=http --add-service=https
    firewall-cmd -q --reload
    ok "80, 443 포트 열기 완료"
  else
    ok "firewalld 가 꺼져 있어서 건너뛰어요."
  fi
fi

# ─────────────────────────────────────────
# 6. HTTPS 인증서 (Let's Encrypt, 자동 갱신)
# ─────────────────────────────────────────
step "6. HTTPS 인증서"
if ! command -v certbot >/dev/null || ! rpm -q python3-certbot-apache mod_ssl >/dev/null 2>&1; then
  echo "  · certbot 설치 중..."
  dnf -y -q install epel-release
  dnf -y -q install certbot python3-certbot-apache mod_ssl
  systemctl restart httpd
fi
MYIP=$(curl -fsS https://api.ipify.org || echo "?")
DNSIP=$(getent hosts "$PROD_DOMAIN" | awk '{print $1}' | head -1 || true)
echo "  · 집 공인 IP: $MYIP / ${PROD_DOMAIN} → ${DNSIP:-조회 안 됨}"
if certbot --apache -n --agree-tos -m "$LETSENCRYPT_EMAIL" --redirect \
     -d "$PROD_DOMAIN" -d "$TEST_DOMAIN"; then
  systemctl enable --now certbot-renew.timer >/dev/null 2>&1 || true
  ok "HTTPS 인증서 발급 완료 (자동 갱신 켜짐)"
  CERT_OK=1
else
  CERT_OK=0
  warn "인증서 발급에 실패했어요. 대부분 '공유기 포트포워딩'이 아직 안 된 경우예요."
  warn "공유기에서 외부 80, 443 → 이 서버($(hostname -I | awk '{print $1}'))로 포트포워딩 후 아래를 실행하세요:"
  warn "    bash $BASE/test/deploy/install.sh cert"
fi

# ─────────────────────────────────────────
# 끝
# ─────────────────────────────────────────
step "완료"
S=$([ "$CERT_OK" = 1 ] && echo https || echo http)
echo "  운영   : ${S}://${PROD_DOMAIN}"
echo "  테스트 : ${S}://${TEST_DOMAIN}"
if [ "$MODE" = "all" ]; then
  echo
  echo "  🔑 관리자 계정 (처음 실행 때만 적용 · 꼭 메모하고 로그인 후 바꾸세요)"
  echo "     운영   : admin / ${PROD_ADMIN_PASSWORD}"
  echo "     테스트 : admin / ${TEST_ADMIN_PASSWORD}"
  echo "     (다시 보기: grep ADMIN_PASSWORD $BASE/.env)"
fi
echo
echo "  업데이트: cd $BASE && ./update.sh test   (운영은 ./update.sh prod)"
