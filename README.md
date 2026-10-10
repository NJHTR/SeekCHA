# Dorm Check

React UI for managing the dorm-check workflow, backed by a Java 17 / Spring Boot service. The UI uses `@seekwd/ui` controls and `@seekwd/ui/styles.css` from the Seekwd workspace.

## Development

```powershell
pnpm install
pnpm --filter @seekwd/dorm-check dev
```

The Vite app uses port `4174` and proxies `/api` to `127.0.0.1:8001`. In a second terminal, start the Java service from `service`:

```sh
export DORMCHECK_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export DORMCHECK_COOKIE_SECURE=false
./mvnw spring-boot:run
```

On Windows PowerShell, start from `service` and run:

```powershell
$env:DORMCHECK_ENCRYPTION_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:DORMCHECK_COOKIE_SECURE = 'false'
$env:DORMCHECK_PYTHON = 'python'
.\mvnw.cmd spring-boot:run
```

Run service tests with `.\mvnw.cmd test`.

The UI falls back to labeled demo data only when the API is absent. A server error shows an unavailable state instead of presenting demo results as live data.

For email verification, configure an SMTP relay before starting the service. The service fails closed when delivery is unavailable; it does not return codes to the browser:

```powershell
$env:DORMCHECK_SMTP_HOST = 'smtp.example.com'
$env:DORMCHECK_SMTP_PORT = '587'
$env:DORMCHECK_SMTP_USERNAME = 'mailer@example.com'
$env:DORMCHECK_SMTP_PASSWORD = '<secret from a protected environment file>'
$env:DORMCHECK_SMTP_AUTH = 'true'
$env:DORMCHECK_SMTP_STARTTLS = 'true'
$env:DORMCHECK_SMTP_SSL = 'false'
$env:DORMCHECK_MAIL_FROM = 'mailer@example.com'
```

For providers that require implicit TLS (for example QQ Mail), use their SSL SMTP port (commonly `465`), set `DORMCHECK_SMTP_SSL=true`, and set `DORMCHECK_SMTP_STARTTLS=false`.

Registration requires an email code and only accepts exact domains from the common-mailbox allowlist: `qq.com`, `foxmail.com`, `163.com`, `126.com`, `yeah.net`, `gmail.com`, `outlook.com`, `hotmail.com`, `live.com`, `icloud.com`, `aliyun.com`, `sina.com`, `sohu.com`, `139.com`, and `189.cn`. Receiving and entering the code proves mailbox access. Login requires the password and a fresh email code; accounts with authenticator-based TOTP enabled also require its current six-digit code. The service applies IP, email, and verification-attempt rate limits before expensive password verification. The browser also records a random per-installation device ID and low-entropy device details for the signed-in device list; these client values can be spoofed and are not treated as authentication factors. TOTP secrets are encrypted with the service encryption key.

## Service Security

- Keep the Java API bound to `127.0.0.1`; do not publish port `8001`.
- The Java service honors forwarded headers for client IP detection. Keep port `8001` reachable only from the local reverse proxy, and configure that proxy to overwrite `X-Forwarded-For`, `X-Forwarded-Host`, and `X-Forwarded-Proto`; never allow clients to reach the service directly.
- Serve the UI and `/api` from one HTTPS origin. Production cookies require `DORMCHECK_COOKIE_SECURE=true`.
- Set `DORMCHECK_ALLOWED_HOST` to the exact public hostname and keep the TLS proxy on the same host.
- Store the Base64-encoded `DORMCHECK_ENCRYPTION_KEY` in a root-owned environment file with mode `0600`. Back it up securely; losing it makes saved campus credentials undecryptable.
- Keep the database directory private to the service account. The service restricts the SQLite directory to mode `0700` and database file to `0600` on POSIX systems.
- Use a dedicated unprivileged systemd user with `NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome=true`, and writable access only to the database directory.
- Never run the API as root, expose the Java service directly, or put campus credentials in command-line arguments, environment variables, access logs, or browser storage.
- The Java service stores campus credentials as AES-GCM ciphertext, stores account passwords with PBKDF2-HMAC-SHA256, uses HttpOnly/SameSite cookies with CSRF validation, applies authentication and task rate limits, and never returns campus credentials from its API.
- Signed-in users can review and revoke device sessions. Device metadata is descriptive only; upstream network-level abuse protection should still be configured at the HTTPS proxy or CDN because application rate limits cannot stop distributed denial-of-service traffic.
- Before giving the service access to the existing script, prepare a sanitized engine copy. This removes the embedded account, password, and mail assignments while leaving the original files untouched:

```sh
python3 tools/prepare_engine.py /root/dev-dowork/dowork/goToBed.py /opt/dorm-check/engine
chown -R root:dormcheck /opt/dorm-check/engine
chmod 0750 /opt/dorm-check/engine
chmod 0640 /opt/dorm-check/engine/goToBed.py /opt/dorm-check/engine/g5116.js
```

The Java web service invokes `worker/worker.py` for verification and checks. The worker receives credentials only through standard input, not process arguments; install the Python dependencies required by the existing sanitized script for the dedicated service user.

## Academic Data Caching

Notices and timetables use a user-scoped multi-level cache. The browser keeps a short-lived session cache (60 seconds), the service keeps a bounded in-memory L1 cache (about five minutes with per-entry jitter), and SQLite stores a non-sensitive L2 response envelope across service restarts. Entries remain eligible for stale-while-revalidate for 15 minutes, so a temporary campus outage does not cause every browser to retry the same login. Expired entries are refreshed through a per-user/per-resource single-flight operation; refresh failures do not overwrite a valid stale response. Cache keys include the encrypted campus credential update timestamp, and saving or deleting credentials invalidates the user's database and memory entries.

The refresh button is rate-limited to three bypasses per user per ten minutes. Normal reads never bypass the service cache. Redis is supported as an optional shared L2 for a future multi-instance deployment; enable it only after provisioning a private Redis instance by setting `DORMCHECK_CACHE_REDIS_ENABLED=true` and `SPRING_DATA_REDIS_URL=redis://...`. Redis failures fall back to SQLite and do not make the API unavailable. Do not put credentials, cookies, or authentication tickets in any cache.

The original script directory contains credentials in several files; do not grant the web-service user access to that directory. Existing cron jobs are not changed by this application.

## Check Queue

Manual checks and cron triggers now enqueue a persistent SQLite job. One campus operation runs at a time; checks, credential verification, notices, and timetables share a persisted access lease and a 60-second gap after completion. Interactive reads/verification return a temporary unavailable response while the gate is busy instead of starting another Python process.

Each user has at most one active check and three actual check attempts per campus day (Asia/Shanghai). A successful check prevents another submission that day. Only a network/service error before submission is retried, at least five minutes later. Invalid credentials, captcha errors, closed attendance periods, and uncertain submission outcomes stop without automatic retries. HTTP 403/429 and account-lock signals pause all campus access for 30 minutes; three consecutive transport/process failures trigger a 15-minute cooldown. Task gaps and cooldowns survive a restart.

Queued and waiting jobs survive service restarts. An interrupted running job is finalized as uncertain without resending; this avoids repeating a submission that the campus server may already have accepted. Jobs expire at the next campus midnight. Pausing a schedule cancels its waiting scheduled job. Final status, history, and notifications are stored atomically; only final outcomes send mail. `GET /api/checks/queue` reports only the signed-in user's active job. The UI polls this local API every ten seconds, without contacting campus.

The queue manages only this Java service. Any independent legacy scripts or cron jobs on the same server remain outside its limit and must be paused or migrated separately. These defaults reduce load but cannot guarantee the campus server will never restrict the shared public IP. Capacity is less than 60 campus operations per hour because runtime is added to the gap; allow queue delay when choosing a schedule time.

## Current Scope

The script adapter is specific to the existing Guangzhou Institute of Science and Technology CAS/check-in flow and its current campus selection logic. Task results are stored in per-user history. The deployment must stay private until SMTP delivery, a real domain, HTTPS reverse proxy, and host firewall policy are configured and tested.
