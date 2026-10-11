export type ApiUser = { id: string; email: string; name: string };
export type ApiRun = { id: string; status: "success" | "failure"; message: string; time: string; duration: string };
export type ApiCheckQueue = { active: boolean; id?: string; status?: "queued" | "running" | "retry_wait"; attempts?: number; max_attempts?: number; not_before?: string; paused?: boolean };
export type ApiNotification = ApiRun & {
  read: boolean;
  type: "run" | "schedule" | "credentials" | "security" | "system";
  title: string;
};
export type ApiCheckPage = { items: ApiRun[]; total: number; page: number; page_size: number; total_pages: number; status: "all" | "success" | "failure" };
export type ApiDashboard = {
  schedule: { enabled: boolean; run_time: string; days_of_week: number[]; timezone: string };
  credential: { configured: boolean; verified: boolean; account_hint: string; updated_at: string | null };
  runs: ApiRun[];
  stats: { total: number; success_count: number; recent_total: number; recent_success_count: number; success_rate: number };
};
export type ApiDevice = { id: string; name: string; platform: string; language: string; timezone: string; screen_size: string; hardware_threads: number | null; device_memory: number | null; created_at: string; last_seen_at: string; current: boolean };
export type ApiAcademicNotice = { title: string; date: string; url: string };
export type ApiAcademicScheduleItem = { course: string; teacher: string; room: string; weekday: string; periods: string; weeks: string; type: string };
export type ApiAcademicDataMeta = { fetched_at: string; cache_status: "fresh" | "stale" | "refreshed" | "stale-fallback"; academic_scope?: string };
export type ApiAdminOverview = {
  admin: { role: "owner" | "operator" | "analyst"; name: string };
  users: { total: number; configured: number; verified: number; scheduled: number };
  runs_today: { total: number; successes: number; failures: number };
  queue: { queued: number; running: number; retry_wait: number };
  feature_use_today: { timetable: number; notices: number };
};
export type ApiAdminUser = { id: string; email: string; name: string; registered_at: string; credential_configured: boolean; credential_verified: boolean; schedule_enabled: boolean; last_run_status: string | null; last_run_at: string | null; admin_role: "owner" | "operator" | "analyst" | null };
export type ApiAdminRun = { id: string; email: string; name: string; status: "success" | "failure"; message: string; time: string; duration_ms: number };
export type ApiAdminQueueItem = { id: string; email: string; name: string; trigger: string; status: string; attempts: number; max_attempts: number; created_at: string; not_before: string; started_at: string; reason: string };
export type ApiAdminFeatureUse = { feature: "notices" | "schedule"; result: "success" | "failure"; time: string; name: string; email: string };
export type ApiAdminAudit = { action: string; remote_address: string; time: string; admin: string };

let csrfToken = "";
let activeUserId = "anonymous";
const browserAcademicCache = new Map<string, { expiresAt: number; value: unknown }>();
const browserAcademicInFlight = new Map<string, Promise<unknown>>();
const BROWSER_ACADEMIC_TTL = 60_000;

function browserCacheKey(kind: string) {
  return `dormcheck:${activeUserId}:academic:${kind}`;
}

function readBrowserAcademicCache<T>(kind: string): T | undefined {
  const key = browserCacheKey(kind);
  const memory = browserAcademicCache.get(key);
  if (memory && memory.expiresAt > Date.now()) return memory.value as T;
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return undefined;
    const parsed = JSON.parse(raw) as { expiresAt: number; value: T };
    if (parsed.expiresAt <= Date.now()) {
      sessionStorage.removeItem(key);
      return undefined;
    }
    browserAcademicCache.set(key, parsed);
    return parsed.value;
  } catch { return undefined; }
}

function writeBrowserAcademicCache<T>(kind: string, value: T, userId = activeUserId) {
  const key = `dormcheck:${userId}:academic:${kind}`;
  const entry = { expiresAt: Date.now() + BROWSER_ACADEMIC_TTL, value };
  browserAcademicCache.set(key, entry);
  try { sessionStorage.setItem(key, JSON.stringify(entry)); } catch { /* storage may be unavailable */ }
}

function clearBrowserAcademicCache() {
  browserAcademicCache.clear();
  browserAcademicInFlight.clear();
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
      const key = sessionStorage.key(index);
      if (key?.includes(":academic:")) sessionStorage.removeItem(key);
    }
  } catch { /* storage may be unavailable */ }
}

function deviceHeaders() {
  let deviceId = "";
  try {
    deviceId = localStorage.getItem("dormcheck_device_id") ?? "";
    if (!/^[0-9a-f-]{36}$/i.test(deviceId)) {
      deviceId = crypto.randomUUID();
      localStorage.setItem("dormcheck_device_id", deviceId);
    }
  } catch { deviceId = crypto.randomUUID(); }
  const headers = new Headers({ "X-Device-ID": deviceId });
  const screenSize = typeof screen === "undefined" ? "" : `${screen.width}x${screen.height}`;
  const values: Record<string, string> = {
    "X-Device-Platform": (navigator as Navigator & { userAgentData?: { platform?: string } }).userAgentData?.platform ?? navigator.platform ?? "",
    "X-Device-Language": navigator.language ?? "",
    "X-Device-Timezone": Intl.DateTimeFormat().resolvedOptions().timeZone ?? "",
    "X-Device-Screen": screenSize,
    "X-Device-CPU": String(navigator.hardwareConcurrency ?? ""),
    "X-Device-Memory": String((navigator as Navigator & { deviceMemory?: number }).deviceMemory ?? ""),
  };
  Object.entries(values).forEach(([name, value]) => { if (value) headers.set(name, value.slice(0, 80)); });
  return headers;
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  deviceHeaders().forEach((value, name) => headers.set(name, value));
  headers.set("Accept", "application/json");
  if (options.body) headers.set("Content-Type", "application/json");
  if (["POST", "PUT", "PATCH", "DELETE"].includes((options.method ?? "GET").toUpperCase()) && csrfToken) {
    headers.set("X-CSRF-Token", csrfToken);
  }
  const response = await fetch(`/api${path}`, { ...options, headers, credentials: "same-origin", cache: "no-store" });
  const contentType = response.headers.get("content-type") ?? "";
  const body = contentType.includes("application/json") ? await response.json() : null;
  if (!response.ok) {
    throw new ApiError(typeof body?.detail === "string" ? body.detail : `Request failed (${response.status})`, response.status);
  }
  return body as T;
}

export const dormApi = {
  async session() {
    const result = await request<{ authenticated: boolean; csrf_token?: string; current_device_id?: string; user?: ApiUser }>("/auth/session");
    csrfToken = result.csrf_token ?? "";
    activeUserId = result.user?.id ?? "anonymous";
    if (!result.authenticated) clearBrowserAcademicCache();
    return result;
  },
  login(email: string, password: string) {
    return request<{ verification_required: true; email: string; totp_required: boolean }>("/auth/login", {
      method: "POST", body: JSON.stringify({ email, password }),
    });
  },
  async verifyLogin(email: string, emailCode: string, totpCode = "") {
    const result = await request<{ authenticated: true; csrf_token: string; current_device_id: string; user: ApiUser }>("/auth/verify", {
      method: "POST", body: JSON.stringify({ email, emailCode, totpCode }),
    });
    csrfToken = result.csrf_token;
    activeUserId = result.user.id;
    clearBrowserAcademicCache();
    return result;
  },
  async sendRegistrationCode(email: string) {
    return request<{ sent: boolean }>("/auth/register-code", {
      method: "POST", body: JSON.stringify({ email }),
    });
  },
  async register(name: string, email: string, password: string, emailCode: string) {
    const result = await request<{ authenticated: true; csrf_token: string; current_device_id: string; user: ApiUser }>("/auth/register", {
      method: "POST", body: JSON.stringify({ name, email, password, emailCode }),
    });
    csrfToken = result.csrf_token;
    activeUserId = result.user.id;
    clearBrowserAcademicCache();
    return result;
  },
  async logout() {
    const result = await request<{ ok: boolean }>("/auth/logout", { method: "POST" });
    csrfToken = "";
    activeUserId = "anonymous";
    clearBrowserAcademicCache();
    return result;
  },
  dashboard: () => request<ApiDashboard>("/dashboard"),
  checks: (page: number, status: ApiCheckPage["status"]) => request<ApiCheckPage>(`/checks?page=${page}&status=${status}`),
  notifications: () => request<{ items: ApiNotification[]; unread_count: number }>("/notifications"),
  async academicNotices(refresh = false) {
    type Response = { items: ApiAcademicNotice[] } & ApiAcademicDataMeta;
    const ownerUserId = activeUserId;
    const key = browserCacheKey("notices");
    if (!refresh) {
      const cached = readBrowserAcademicCache<Response>("notices");
      if (cached) return cached;
    }
    const active = browserAcademicInFlight.get(key) as Promise<Response> | undefined;
    if (active) return active;
    const pending = request<Response>(`/academic/notices${refresh ? "?refresh=true" : ""}`)
      .then((result) => {
        writeBrowserAcademicCache("notices", result, ownerUserId);
        return result;
      })
      .finally(() => browserAcademicInFlight.delete(key));
    browserAcademicInFlight.set(key, pending);
    return pending;
  },
  async academicSchedule(refresh = false) {
    type Response = { items: ApiAcademicScheduleItem[] } & ApiAcademicDataMeta;
    const ownerUserId = activeUserId;
    const key = browserCacheKey("schedule");
    if (!refresh) {
      const cached = readBrowserAcademicCache<Response>("schedule");
      if (cached) return cached;
    }
    const active = browserAcademicInFlight.get(key) as Promise<Response> | undefined;
    if (active) return active;
    const pending = request<Response>(`/academic/schedule${refresh ? "?refresh=true" : ""}`)
      .then((result) => {
        writeBrowserAcademicCache("schedule", result, ownerUserId);
        return result;
      })
      .finally(() => browserAcademicInFlight.delete(key));
    browserAcademicInFlight.set(key, pending);
    return pending;
  },
  markNotificationsRead: () => request<{ updated: number }>("/notifications/read", { method: "PUT" }),
  markNotificationRead: (id: string) => request<{ updated: boolean }>(`/notifications/${encodeURIComponent(id)}/read`, { method: "PUT" }),
  saveCredentials: (account: string, password: string) => request<{ configured: boolean; account_hint: string }>("/credentials", {
    method: "PUT", body: JSON.stringify({ account, password }),
  }),
  verifyCredentials: () => request<{ verified: boolean; message: string }>("/credentials/verify", { method: "POST"}),
  saveSchedule: (schedule: ApiDashboard["schedule"]) => request<ApiDashboard["schedule"]>("/schedule", {
    method: "PUT", body: JSON.stringify(schedule),
  }),
  runCheck: () => request<{ id: string; status: "queued" | "running" | "retry_wait" | "success"; message: string; duplicate: boolean }>("/checks", { method: "POST" }),
  checkQueue: () => request<ApiCheckQueue>("/checks/queue"),
  totpStatus: () => request<{ enabled: boolean }>("/auth/totp"),
  devices: () => request<ApiDevice[]>("/auth/devices"),
  revokeDevice: (id: string) => request<{ revoked: boolean }>(`/auth/devices/${encodeURIComponent(id)}`, { method: "DELETE" }),
  beginTotp: () => request<{ secret: string; otpauth_url: string }>("/auth/totp/setup", { method: "POST" }),
  confirmTotp: (code: string) => request<{ enabled: boolean }>("/auth/totp/confirm", { method: "POST", body: JSON.stringify({ code }) }),
  adminMe: () => request<{ role: "owner" | "operator" | "analyst"; name: string }>("/admin/me"),
  adminOverview: () => request<ApiAdminOverview>("/admin/overview"),
  adminUsers: (page = 1, query = "") => request<{ items: ApiAdminUser[]; page: number; page_size: number; total: number }>(`/admin/users?page=${page}&q=${encodeURIComponent(query)}`),
  adminRuns: (page = 1, status: "all" | "success" | "failure" = "all") => request<{ items: ApiAdminRun[]; page: number; page_size: number; total: number }>(`/admin/runs?page=${page}&status=${status}`),
  adminQueue: () => request<{ items: ApiAdminQueueItem[] }>("/admin/queue"),
  adminAudit: () => request<{ items: ApiAdminAudit[] }>("/admin/audit"),
  adminFeatures: (page = 1, feature: "all" | "notices" | "schedule" = "all") => request<{ items: ApiAdminFeatureUse[]; page: number; page_size: number; total: number }>(`/admin/features?page=${page}&feature=${feature}`),
  adminSetRole: (userId: string, role: "none" | "owner" | "operator" | "analyst", reason: string) => request<{ updated: boolean; role: string }>("/admin/roles", { method: "PUT", body: JSON.stringify({ userId, role, reason }) }),
};
