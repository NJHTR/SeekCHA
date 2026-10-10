#!/usr/bin/env bash
set -euo pipefail

ENV_FILE=/etc/dorm-check/dorm-check.env

if [[ $(id -u) -ne 0 ]]; then
  echo "请使用 root 执行此脚本。" >&2
  exit 1
fi

read -r -s -p "请输入新生成的 QQ 邮箱 SMTP 授权码（输入不会显示）: " SMTP_PASSWORD
echo
if [[ -z "$SMTP_PASSWORD" ]]; then
  echo "授权码不能为空。" >&2
  exit 1
fi

TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
chmod 600 "$TMP"

awk -F= '
BEGIN {
  values["DORMCHECK_SMTP_HOST"]="smtp.qq.com"
  values["DORMCHECK_SMTP_PORT"]="465"
  values["DORMCHECK_SMTP_AUTH"]="true"
  values["DORMCHECK_SMTP_STARTTLS"]="false"
  values["DORMCHECK_SMTP_SSL"]="true"
  values["DORMCHECK_SMTP_USERNAME"]="3034983143@qq.com"
  values["DORMCHECK_MAIL_FROM"]="3034983143@qq.com"
}
{
  key=$1
  if (key in values) {
    print key "=" values[key]
    seen[key]=1
  } else if (key != "DORMCHECK_SMTP_PASSWORD") {
    print $0
  }
}
END {
  for (key in values) if (!(key in seen)) print key "=" values[key]
}
' "$ENV_FILE" > "$TMP"
printf 'DORMCHECK_SMTP_PASSWORD=%s\n' "$SMTP_PASSWORD" >> "$TMP"
unset SMTP_PASSWORD

install -m 600 -o root -g root "$TMP" "$ENV_FILE"
systemctl restart dorm-check.service

for _ in {1..30}; do
  if curl --fail --silent --max-time 2 \
    -H "Host: njhtr.site" \
    http://127.0.0.1:8001/api/health >/dev/null; then
    echo "SMTP 配置已写入，查寝服务已正常重启。"
    echo "接下来请在网站注册页发送验证码进行实测。"
    exit 0
  fi
  sleep 1
done

echo "服务重启后未在 30 秒内通过健康检查。" >&2
journalctl -u dorm-check.service --no-pager -n 30 >&2
exit 1
