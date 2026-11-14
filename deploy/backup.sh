#!/bin/bash
# 회원 DB·자료·암호화 키 백업 → backups/blog-날짜.tar.gz (최근 14개만 보관)
#  매일 새벽 4시 자동 백업:  crontab -e  →  0 4 * * * /opt/blog/backup.sh
set -e
cd "$(dirname "$0")"
mkdir -p backups
tar czf "backups/blog-$(date +%Y%m%d-%H%M).tar.gz" data/prod data/test .env
ls -1t backups/blog-*.tar.gz | tail -n +15 | xargs -r rm -f
echo "✅ 백업 완료: $(ls -1t backups/blog-*.tar.gz | head -1)"
