PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  password_salt BLOB NOT NULL,
  password_hash BLOB NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  csrf_token TEXT NOT NULL,
  expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);
CREATE TABLE IF NOT EXISTS credentials (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  ciphertext BLOB NOT NULL,
  account_hint TEXT NOT NULL,
  verified_at TEXT,
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS schedules (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  enabled INTEGER NOT NULL DEFAULT 0,
  run_time TEXT NOT NULL DEFAULT '21:40',
  days_of_week TEXT NOT NULL DEFAULT '1,2,3,4,5,6,7',
  timezone TEXT NOT NULL DEFAULT 'Asia/Shanghai',
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS runs (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status TEXT NOT NULL CHECK(status IN ('success', 'failure')),
  message TEXT NOT NULL,
  started_at TEXT NOT NULL,
  duration_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS runs_user_time ON runs(user_id, started_at DESC);
CREATE TABLE IF NOT EXISTS check_jobs (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  trigger_type TEXT NOT NULL CHECK(trigger_type IN ('manual', 'scheduled')),
  status TEXT NOT NULL CHECK(status IN ('queued', 'running', 'retry_wait', 'success', 'failure')),
  attempts INTEGER NOT NULL DEFAULT 0,
  max_attempts INTEGER NOT NULL DEFAULT 3,
  created_at TEXT NOT NULL,
  not_before TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  started_at TEXT,
  finished_at TEXT,
  last_error TEXT
);
CREATE INDEX IF NOT EXISTS check_jobs_ready ON check_jobs(status, not_before, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS check_jobs_one_active_per_user ON check_jobs(user_id)
  WHERE status IN ('queued', 'running', 'retry_wait');
CREATE TABLE IF NOT EXISTS check_queue_state (
  id INTEGER PRIMARY KEY CHECK(id = 1),
  not_before INTEGER NOT NULL DEFAULT 0,
  lease_until INTEGER NOT NULL DEFAULT 0,
  lease_token TEXT,
  cooldown_until INTEGER NOT NULL DEFAULT 0,
  transient_failures INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO check_queue_state(id) VALUES(1);
CREATE TABLE IF NOT EXISTS notifications (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  run_id TEXT UNIQUE REFERENCES runs(id) ON DELETE CASCADE,
  type TEXT NOT NULL DEFAULT 'run',
  title TEXT NOT NULL DEFAULT '',
  message TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  read_at TEXT
);
CREATE INDEX IF NOT EXISTS notifications_user_unread ON notifications(user_id, read_at, created_at DESC);
INSERT OR IGNORE INTO notifications(id,user_id,run_id,created_at,read_at)
  SELECT id,user_id,id,started_at,started_at FROM runs;
CREATE TABLE IF NOT EXISTS rate_limits (
  bucket TEXT PRIMARY KEY,
  window_start INTEGER NOT NULL,
  attempts INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS academic_cache (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  cache_type TEXT NOT NULL CHECK(cache_type IN ('notices', 'schedule')),
  credential_version TEXT NOT NULL,
  payload TEXT NOT NULL,
  fetched_at INTEGER NOT NULL,
  fresh_until INTEGER NOT NULL,
  stale_until INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(user_id, cache_type)
);
CREATE INDEX IF NOT EXISTS academic_cache_expiry ON academic_cache(stale_until);
CREATE TABLE IF NOT EXISTS email_codes (
  email TEXT NOT NULL,
  purpose TEXT NOT NULL CHECK(purpose IN ('register', 'login')),
  code_hash TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(email, purpose)
);
CREATE TABLE IF NOT EXISTS user_totp (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  secret_ciphertext BLOB NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_devices (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_key_hash TEXT NOT NULL,
  device_name TEXT NOT NULL,
  platform TEXT NOT NULL,
  user_agent TEXT NOT NULL,
  language TEXT NOT NULL,
  timezone TEXT NOT NULL,
  screen_size TEXT NOT NULL,
  hardware_threads INTEGER,
  device_memory INTEGER,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  UNIQUE(user_id, device_key_hash)
);
CREATE INDEX IF NOT EXISTS auth_devices_user_seen ON auth_devices(user_id,last_seen_at DESC);
CREATE TABLE IF NOT EXISTS auth_device_sessions (
  token_hash TEXT PRIMARY KEY REFERENCES sessions(token_hash) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES auth_devices(id) ON DELETE CASCADE
);
