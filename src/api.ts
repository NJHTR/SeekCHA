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
    type Response = { items: ApiAcademicNotice[]; fetched_at: string };
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
    type Response = { items: ApiAcademicScheduleItem[]; fetched_at: string };
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
};
