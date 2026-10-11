import { useEffect, useRef, useState, type CSSProperties, type FormEvent, type ReactNode } from "react";
import {
  Activity, Bell, CalendarClock, CalendarDays, Check, CheckCheck, ChevronDown, ChevronLeft, ChevronRight, CircleAlert, ExternalLink, Github,
  CircleHelp, Clock3, DoorOpen, Eye, EyeOff, History, House, KeyRound, LockKeyhole,
  LogOut, Moon, Newspaper, Play, RefreshCw, Save, Settings2, ShieldCheck, UserRound,
  Sun,
} from "lucide-react";
import {
  Button, Checkbox, Dialog, IconButton, Menu, Notification, Notice,
  SegmentedControl, SidebarItem, SidebarSection, StatusBadge, Switch, TextField,
  Toolbar, ToolbarDivider, Tooltip, WindowFrame,
} from "@seekwd/ui";
import { dormApi, type ApiAcademicDataMeta, type ApiAcademicNotice, type ApiAcademicScheduleItem, type ApiCheckQueue, type ApiDashboard, type ApiDevice, type ApiNotification, type ApiRun, type ApiUser, type ApiAdminOverview, type ApiAdminUser, type ApiAdminRun, type ApiAdminQueueItem, type ApiAdminFeatureUse, type ApiAdminAudit } from "./api";
import { DEFAULT_INTERFACE_SETTINGS, WorkspaceSettings, type InterfaceAccent, type InterfaceDensity, type InterfaceLayout, type InterfaceSettingsValue, type InterfaceTheme } from "./WorkspaceSettings";

type Page = "overview" | "history" | "notices" | "timetable" | "schedule" | "account" | "admin";
type Result = "success" | "failure" | "running";
type CheckRecord = { id: string | number; time: string; status: Exclude<Result, "running">; message: string; duration: string };
type AuthMode = "login" | "register";
type RuntimeMode = "checking" | "demo" | "connected" | "unavailable";

const INTERFACE_SETTINGS_KEY = "seekcha-interface-settings";

function loadInterfaceSettings(): InterfaceSettingsValue {
  if (typeof window === "undefined") return DEFAULT_INTERFACE_SETTINGS;
  try {
    const parsed = JSON.parse(window.localStorage.getItem(INTERFACE_SETTINGS_KEY) ?? "null") as Partial<InterfaceSettingsValue> | null;
    if (!parsed || typeof parsed !== "object") return DEFAULT_INTERFACE_SETTINGS;
    const theme: InterfaceTheme = parsed.theme === "soft" || parsed.theme === "dark" ? parsed.theme : "light";
    const density: InterfaceDensity = parsed.density === "compact" || parsed.density === "relaxed" ? parsed.density : "balanced";
    const layout: InterfaceLayout = parsed.layout === "focus" || parsed.layout === "split" ? parsed.layout : "wide";
    const accent: InterfaceAccent = parsed.accent === "blue" || parsed.accent === "amber" ? parsed.accent : "green";
    const fontScale = typeof parsed.fontScale === "number" && Number.isFinite(parsed.fontScale) ? Math.min(1.25, Math.max(0.9, parsed.fontScale)) : 1;
    return { fontScale, density, layout, theme, accent };
  } catch { return DEFAULT_INTERFACE_SETTINGS; }
}

function interfaceStyle(settings: InterfaceSettingsValue): CSSProperties {
  const density = settings.density === "compact" ? { gap: "12px", x: "16px", y: "14px" } : settings.density === "relaxed" ? { gap: "22px", x: "25px", y: "22px" } : { gap: "16px", x: "20px", y: "18px" };
  const width = settings.layout === "focus" ? "900px" : settings.layout === "split" ? "1320px" : "1170px";
  const accent = settings.accent === "blue" ? "#3f6f9c" : settings.accent === "amber" ? "#b87927" : "#25734d";
  return { "--ui-scale": settings.fontScale, "--layout-gap": density.gap, "--card-padding-x": density.x, "--card-padding-y": density.y, "--content-max-width": width, "--accent": accent } as CSSProperties;
}

const pages: { id: Page; label: string; icon: ReactNode }[] = [
  { id: "overview", label: "总览", icon: <House /> },
  { id: "history", label: "查寝记录", icon: <History /> },
  { id: "notices", label: "教务公告", icon: <Newspaper /> },
  { id: "timetable", label: "我的课表", icon: <CalendarDays /> },
  { id: "schedule", label: "定时任务", icon: <CalendarClock /> },
  { id: "account", label: "账号设置", icon: <KeyRound /> },
];

const weekdays = [
  { value: 1, short: "一", label: "星期一" },
  { value: 2, short: "二", label: "星期二" },
  { value: 3, short: "三", label: "星期三" },
  { value: 4, short: "四", label: "星期四" },
  { value: 5, short: "五", label: "星期五" },
  { value: 6, short: "六", label: "星期六" },
  { value: 7, short: "日", label: "星期日" },
];
const displayWeekdays = [weekdays[6], ...weekdays.slice(0, 6)];

function formatScheduleDays(days: number[]) {
  if (days.length === 7) return "每天执行";
  return `每周${displayWeekdays.filter(({ value }) => days.includes(value)).map(({ short }) => short).join("、")}`;
}

function formatClock(date: Date) {
  return new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }).format(date);
}

function formatDay(date: Date) {
  return new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", year: "numeric", month: "long", day: "numeric", weekday: "long" }).format(date);
}

function formatEyebrow(date: Date) {
  return new Intl.DateTimeFormat("en-US", { timeZone: "Asia/Shanghai", weekday: "long", month: "long", day: "2-digit" }).format(date).toUpperCase();
}

function nextRunDay(date: Date, scheduledTime: string, daysOfWeek: number[]) {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Shanghai", hour: "2-digit", minute: "2-digit", hour12: false }).formatToParts(date);
  const currentMinutes = Number(parts.find((part) => part.type === "hour")?.value ?? 0) * 60 + Number(parts.find((part) => part.type === "minute")?.value ?? 0);
  const [hour, minute] = scheduledTime.split(":").map(Number);
  const weekday = new Intl.DateTimeFormat("en-US", { timeZone: "Asia/Shanghai", weekday: "short" }).format(date);
  const today = ({ Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 } as Record<string, number>)[weekday] ?? 1;
  for (let offset = 0; offset < 7; offset++) {
    const candidate = ((today + offset - 1) % 7) + 1;
    if (daysOfWeek.includes(candidate) && (offset > 0 || currentMinutes < hour * 60 + minute)) {
      if (offset === 0) return "今天";
      if (offset === 1) return "明天";
      return `周${weekdays[candidate - 1].short}`;
    }
  }
  return "下周";
}

function toRecord(run: ApiRun): CheckRecord {
  const date = new Date(run.time);
  const formatted = new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }).format(date).replaceAll("/", "-");
  return { id: run.id, time: formatted, status: run.status, message: run.message, duration: run.duration };
}

function formatNotificationTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}

export function App() {
  const [page, setPage] = useState<Page>("overview");
  const [accountSection, setAccountSection] = useState<"campus" | "security" | "devices">("campus");
  const [authMode, setAuthMode] = useState<AuthMode | null>(null);
  const [time, setTime] = useState(() => new Date());
  const [records, setRecords] = useState<CheckRecord[]>([]);
  const [recordTotal, setRecordTotal] = useState(0);
  const [recentSuccessCount, setRecentSuccessCount] = useState(0);
  const [recentSuccessRate, setRecentSuccessRate] = useState(0);
  const [historyRecords, setHistoryRecords] = useState<CheckRecord[]>([]);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyPage, setHistoryPage] = useState(1);
  const [historyPages, setHistoryPages] = useState(0);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyRevision, setHistoryRevision] = useState(0);
  const [academicNotices, setAcademicNotices] = useState<ApiAcademicNotice[]>([]);
  const [academicNoticesMeta, setAcademicNoticesMeta] = useState<ApiAcademicDataMeta | null>(null);
  const [academicNoticesLoading, setAcademicNoticesLoading] = useState(false);
  const [academicNoticesError, setAcademicNoticesError] = useState("");
  const [academicNoticesRevision, setAcademicNoticesRevision] = useState(0);
  const [academicSchedule, setAcademicSchedule] = useState<ApiAcademicScheduleItem[]>([]);
  const [academicScheduleMeta, setAcademicScheduleMeta] = useState<ApiAcademicDataMeta | null>(null);
  const [academicScheduleLoading, setAcademicScheduleLoading] = useState(false);
  const [academicScheduleError, setAcademicScheduleError] = useState("");
  const [academicScheduleRevision, setAcademicScheduleRevision] = useState(0);
  const [scheduled, setScheduled] = useState(false);
  const [scheduledTime, setScheduledTime] = useState("21:40");
  const [scheduleDays, setScheduleDays] = useState([1, 2, 3, 4, 5, 6, 7]);
  const [accountConfigured, setAccountConfigured] = useState(false);
  const [accountVerified, setAccountVerified] = useState(false);
  const [accountHint, setAccountHint] = useState("");
  const [runtimeMode, setRuntimeMode] = useState<RuntimeMode>("checking");
  const [user, setUser] = useState<ApiUser | null>(null);
  const [adminRole, setAdminRole] = useState<"owner" | "operator" | "analyst" | null>(null);
  const [currentDeviceId, setCurrentDeviceId] = useState("");
  const [isChecking, setIsChecking] = useState(false);
  const [checkQueue, setCheckQueue] = useState<ApiCheckQueue>({ active: false });
  const submittedJob = useRef<string | undefined>(undefined);
  const [checkRequested, setCheckRequested] = useState(0);
  const [notice, setNotice] = useState("");
  const [notificationItems, setNotificationItems] = useState<ApiNotification[]>([]);
  const [notificationUnread, setNotificationUnread] = useState(0);
  const [notificationsLoading, setNotificationsLoading] = useState(false);
  const [recordFilter, setRecordFilter] = useState<"all" | "success" | "failure">("all");
  const [selectedRecord, setSelectedRecord] = useState<CheckRecord | null>(null);
  const [interfaceSettingsOpen, setInterfaceSettingsOpen] = useState(false);
  const [interfaceSettings, setInterfaceSettings] = useState<InterfaceSettingsValue>(loadInterfaceSettings);
  const theme = interfaceSettings.theme;
  const [authError, setAuthError] = useState("");

  useEffect(() => {
    if (!user || runtimeMode !== "connected") { setAdminRole(null); return; }
    let active = true;
    dormApi.adminMe().then((result) => { if (active) setAdminRole(result.role); }).catch(() => { if (active) setAdminRole(null); });
    return () => { active = false; };
  }, [user?.id, runtimeMode]);

  useEffect(() => {
    window.localStorage.setItem(INTERFACE_SETTINGS_KEY, JSON.stringify(interfaceSettings));
  }, [interfaceSettings]);

  const applyDashboard = (data: ApiDashboard) => {
    setRecords(data.runs.map(toRecord));
    setRecordTotal(data.stats.total);
    setRecentSuccessCount(data.stats.recent_success_count);
    setRecentSuccessRate(data.stats.success_rate);
    setScheduled(data.schedule.enabled);
    setScheduledTime(data.schedule.run_time);
    setScheduleDays(data.schedule.days_of_week);
    setAccountConfigured(data.credential.configured);
    setAccountVerified(data.credential.verified);
    setAccountHint(data.credential.account_hint);
  };

  const refreshDashboard = async () => applyDashboard(await dormApi.dashboard());
  const refreshNotifications = async () => {
    setNotificationsLoading(true);
    try {
      const data = await dormApi.notifications();
      setNotificationItems(data.items);
      setNotificationUnread(data.unread_count);
    } finally { setNotificationsLoading(false); }
  };

  useEffect(() => {
    if (!user || runtimeMode !== "connected") { setCheckQueue({ active: false }); return; }
    let active = true;
    let previousJob: string | undefined;
    let timer: number | undefined;
    const poll = async () => {
      try {
        const status = await dormApi.checkQueue();
        if (!active) return;
        setCheckQueue(status);
        if ((previousJob || submittedJob.current) && !status.active) {
          await refreshDashboard();
          setHistoryRevision((revision) => revision + 1);
          void refreshNotifications().catch(() => {});
          submittedJob.current = undefined;
        }
        previousJob = status.active ? status.id : undefined;
      } catch { /* Keep the last known queue state until the next refresh. */ }
      finally { if (active) timer = window.setTimeout(poll, 10_000); }
    };
    void poll();
    return () => { active = false; if (timer !== undefined) window.clearTimeout(timer); };
  }, [user?.id, runtimeMode]);

  useEffect(() => {
    const timer = window.setInterval(() => setTime(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    let active = true;
    dormApi.session().then(async (session) => {
      if (!active) return;
      setRuntimeMode("connected");
      if (session.authenticated && session.user) {
        setUser(session.user);
        setCurrentDeviceId(session.current_device_id ?? "");
        setAuthMode(null);
        try { await refreshDashboard(); } catch { if (active) setNotice("无法读取查寝数据，请重新登录"); }
      } else {
        setUser(null);
        setNotificationItems([]);
        setNotificationUnread(0);
        setRecords([]);
        setRecordTotal(0);
        setRecentSuccessCount(0);
        setRecentSuccessRate(0);
        setHistoryRecords([]);
        setHistoryTotal(0);
        setHistoryPages(0);
        setAcademicNotices([]);
        setAcademicNoticesError("");
        setAcademicSchedule([]);
        setAcademicScheduleError("");
        setScheduled(false);
        setAccountConfigured(false);
        setAccountVerified(false);
        setAccountHint("");
      }
    }).catch((error) => {
      if (!active) return;
      setRuntimeMode(error?.status >= 500 ? "unavailable" : "demo");
    });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!user || runtimeMode !== "connected") {
      setNotificationItems([]);
      setNotificationUnread(0);
      return;
    }
    let active = true;
    const refresh = () => dormApi.notifications().then((data) => {
      if (active) { setNotificationItems(data.items); setNotificationUnread(data.unread_count); }
    }).catch(() => {});
    void refresh();
    const timer = window.setInterval(() => void refresh(), 60000);
    return () => { active = false; window.clearInterval(timer); };
  }, [user?.id, runtimeMode]);

  useEffect(() => {
    if (page !== "history" || !user || runtimeMode !== "connected") return;
    let active = true;
    setHistoryLoading(true);
    dormApi.checks(historyPage, recordFilter).then((data) => {
      if (!active) return;
      setHistoryRecords(data.items.map(toRecord));
      setHistoryTotal(data.total);
      setHistoryPages(data.total_pages);
      if (data.page !== historyPage) setHistoryPage(data.page);
    }).catch((error) => {
      if (active) setNotice(error instanceof Error ? error.message : "无法读取查寝记录");
    }).finally(() => {
      if (active) setHistoryLoading(false);
    });
    return () => { active = false; };
  }, [page, user?.id, runtimeMode, historyPage, recordFilter, historyRevision]);

  useEffect(() => {
    if (page !== "notices" || !user || runtimeMode !== "connected") return;
    let active = true;
    setAcademicNoticesLoading(true);
    setAcademicNoticesError("");
    dormApi.academicNotices(academicNoticesRevision > 0).then((data) => {
      if (active) { setAcademicNotices(data.items); setAcademicNoticesMeta(data); }
    }).catch((error) => {
      if (active) setAcademicNoticesError(error instanceof Error ? error.message : "无法读取教务公告");
    }).finally(() => {
      if (active) setAcademicNoticesLoading(false);
    });
    return () => { active = false; };
  }, [page, user?.id, runtimeMode, academicNoticesRevision]);

  useEffect(() => {
    if (page !== "timetable" || !user || runtimeMode !== "connected") return;
    let active = true;
    setAcademicScheduleLoading(true);
    setAcademicScheduleError("");
    dormApi.academicSchedule(academicScheduleRevision > 0).then((data) => {
      if (active) { setAcademicSchedule(data.items); setAcademicScheduleMeta(data); }
    }).catch((error) => {
      if (active) setAcademicScheduleError(error instanceof Error ? error.message : "无法读取我的课表");
    }).finally(() => {
      if (active) setAcademicScheduleLoading(false);
    });
    return () => { active = false; };
  }, [page, user?.id, runtimeMode, academicScheduleRevision]);

  useEffect(() => {
    if (!checkRequested) return;
    setIsChecking(true);
    const timer = window.setTimeout(() => {
      const record: CheckRecord = {
        id: Date.now(),
        time: new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }).format(new Date()).replaceAll("/", "-"),
        status: "success",
        message: "演示任务完成，实际查寝需连接服务端",
        duration: "8.2 秒",
      };
      setRecords((current) => [record, ...current]);
      setIsChecking(false);
      setNotice("演示任务已完成");
    }, 1200);
    return () => window.clearTimeout(timer);
  }, [checkRequested]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(""), 3200);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const runCheck = async () => {
    if (isChecking || checkQueue.active) return;
    if (!user) {
      setNotice("登录后才能运行查寝任务");
      setAuthMode("login");
      return;
    }
    if (runtimeMode === "connected") {
      if (!accountConfigured || !accountVerified) {
        setNotice(accountConfigured ? "请先验证校园平台账号" : "请先配置校园平台账号");
        setPage("account");
        return;
      }
      setIsChecking(true);
      try {
        const result = await dormApi.runCheck();
        if (result.status !== "success") {
          submittedJob.current = result.id;
          setCheckQueue({ active: true, id: result.id, status: result.status });
        }
        else void refreshDashboard().catch(() => {});
        setNotice(result.message);
      } catch (error) {
        setNotice(error instanceof Error ? error.message : "查寝任务未能启动");
      } finally {
        setIsChecking(false);
      }
      return;
    }
    setCheckRequested((value) => value + 1);
  };

  const handleAuth = async (values: { name: string; email: string; password: string; emailCode: string; totpCode: string; phase: "credentials" | "verification" }) => {
    setAuthError("");
    try {
      if (authMode === "register") {
        const result = await dormApi.register(values.name, values.email, values.password, values.emailCode);
        setUser(result.user);
        setCurrentDeviceId(result.current_device_id);
        setAuthMode(null);
        await refreshDashboard();
        setNotice("邮箱验证通过，账户已创建");
        return { complete: true as const, user: result.user };
      }
      if (values.phase === "credentials") {
        const pending = await dormApi.login(values.email, values.password);
        return { complete: false as const, email: pending.email, totpRequired: pending.totp_required };
      }
      const result = await dormApi.verifyLogin(values.email, values.emailCode, values.totpCode);
      setUser(result.user);
      setCurrentDeviceId(result.current_device_id);
      setAuthMode(null);
      await refreshDashboard();
      setNotice("二次认证通过，登录成功");
      return { complete: true as const, user: result.user };
    } catch (error) {
      setAuthError(error instanceof Error ? error.message : "认证失败，请重试");
      throw error;
    }
  };

  const sendRegistrationCode = async (email: string) => {
    await dormApi.sendRegistrationCode(email);
  };

  const signOut = async () => {
    if (runtimeMode === "connected") {
      try { await dormApi.logout(); } catch { /* Local state is still cleared below. */ }
      setUser(null);
      submittedJob.current = undefined;
      setCurrentDeviceId("");
      setAuthMode("login");
      setNotificationItems([]);
      setNotificationUnread(0);
      setRecords([]);
      setRecordTotal(0);
      setRecentSuccessCount(0);
      setRecentSuccessRate(0);
      setHistoryRecords([]);
      setHistoryTotal(0);
      setHistoryPage(1);
      setHistoryPages(0);
      setAcademicNotices([]);
      setAcademicNoticesError("");
      setAcademicSchedule([]);
      setAcademicScheduleError("");
      setAccountConfigured(false);
      setAccountVerified(false);
      setAccountHint("");
      setPage("overview");
    }
  };

  const navigate = (destination: Page) => {
    if (destination === "admin" && !adminRole) {
      setNotice("管理员权限不足");
      return;
    }
    if (!user && destination !== "overview") {
      setNotice("登录后才能查看此页面");
      setAuthMode("login");
      return;
    }
    setPage(destination);
  };

  const saveSchedule = async () => {
    if (!scheduleDays.length) { setNotice("至少选择一个执行日期"); return; }
    if (runtimeMode === "connected") {
      try {
        const schedule = await dormApi.saveSchedule({ enabled: scheduled, run_time: scheduledTime, days_of_week: scheduleDays, timezone: "Asia/Shanghai" });
        setScheduled(schedule.enabled);
        setScheduleDays(schedule.days_of_week);
        setNotice("定时配置已保存");
        void refreshNotifications();
      } catch (error) { setNotice(error instanceof Error ? error.message : "保存配置失败"); }
    } else setNotice("定时配置已更新（演示）");
  };

  const saveAccount = async (account: string, password: string) => {
    if (runtimeMode === "connected") {
      try {
        const saved = await dormApi.saveCredentials(account, password);
        setAccountConfigured(saved.configured);
        setAccountVerified(false);
        setAccountHint(saved.account_hint);
        setNotice("校园账号凭据已加密保存");
        void refreshNotifications();
      } catch (error) { setNotice(error instanceof Error ? error.message : "保存账号失败"); }
    } else setNotice("演示模式不会发送或保存账号密码");
  };

  const markAllNotificationsRead = async () => {
    try { await dormApi.markNotificationsRead(); await refreshNotifications(); }
    catch (error) { setNotice(error instanceof Error ? error.message : "无法更新通知状态"); }
  };

  const openNotification = (item: ApiNotification) => {
    if (item.type === "run" || !item.type) setSelectedRecord(toRecord(item));
    else if (item.type === "schedule") navigate("schedule");
    else if (item.type === "security") { setAccountSection("security"); navigate("account"); }
    else { setAccountSection("campus"); navigate("account"); }
    void dormApi.markNotificationRead(item.id).then(refreshNotifications).catch(() => {});
  };

  if (runtimeMode === "checking") return <main className="app-loading" aria-label="正在连接查寝服务"><span className="loading-mark"><Moon /></span><strong>查寝中心</strong><span>正在检查服务连接…</span></main>;
  return (
    <div className={`dorm-app interface-density-${interfaceSettings.density} interface-layout-${interfaceSettings.layout}`} data-sk-theme={theme} style={interfaceStyle(interfaceSettings)}>
      <WindowFrame className="dorm-window" title="SeekCHA"
        toolbar={<Toolbar className="topbar-actions">
          <Tooltip content={theme === "dark" ? "切换浅色外观" : "切换深色外观"}><IconButton label={theme === "dark" ? "切换浅色外观" : "切换深色外观"} onClick={() => setInterfaceSettings((current) => ({ ...current, theme: current.theme === "dark" ? "light" : "dark" }))}>{theme === "dark" ? <Sun /> : <Moon />}</IconButton></Tooltip>
          <NotificationCenter authenticated={Boolean(user)} items={notificationItems} unreadCount={notificationUnread} loading={notificationsLoading} onRefresh={refreshNotifications} onMarkAllRead={markAllNotificationsRead} onOpenNotification={openNotification} onSignIn={() => setAuthMode("login")} onViewAll={() => navigate("history")} />
          <ToolbarDivider />
          {user ? <Menu label="账户操作" icon={<UserRound />} iconOnly items={[{ label: "退出登录", icon: <LogOut />, onSelect: () => void signOut() }]} /> : <Button size="small" variant="primary" onClick={() => setAuthMode("login")}>登录 / 注册</Button>}
        </Toolbar>}
        sidebar={<div className="account-sidebar-layout"><div className="dorm-sidebar">
        <SidebarSection label="工作区"><nav className="side-nav" aria-label="工作区导航">
          {pages.filter((item) => ["overview", "notices", "timetable"].includes(item.id)).map((item) => <SidebarItem key={item.id} icon={!user && item.id !== "overview" ? <LockKeyhole /> : item.icon} active={page === item.id} onClick={() => navigate(item.id)}>{item.label}</SidebarItem>)}
          {adminRole ? <SidebarItem icon={<ShieldCheck />} active={page === "admin"} onClick={() => navigate("admin")}>管理后台</SidebarItem> : null}
        </nav></SidebarSection>
        <div className="sidebar-account">
          {user ? <SidebarItem icon={<UserRound />} active={page === "account"} title={user.email} onClick={() => navigate("account")}><span className="sidebar-account-copy"><strong>{user.name}</strong><small>{user.email}</small></span></SidebarItem> : <Button className="sidebar-signin" variant="quiet" leadingIcon={<UserRound />} onClick={() => setAuthMode("login")}>登录 / 注册</Button>}
        </div>
        </div></div>}
      >
      <div className="dorm-main">
        <header className="topbar">
          <div className="breadcrumbs"><button className="breadcrumb-link" type="button" onClick={() => navigate("overview")}>工作区</button><span>/</span><strong>{page === "admin" ? "管理后台" : pages.find((item) => item.id === page)?.label}</strong></div>
          <nav className="breadcrumb-actions" aria-label="快捷入口">
            <Tooltip content="查寝记录"><IconButton label="查寝记录" active={page === "history"} onClick={() => navigate("history")}><History /></IconButton></Tooltip>
            <Tooltip content="定时任务"><IconButton label="定时任务" active={page === "schedule"} onClick={() => navigate("schedule")}><CalendarClock /></IconButton></Tooltip>
            <Tooltip content="账号设置"><IconButton label="账号设置" active={page === "account"} onClick={() => navigate("account")}><KeyRound /></IconButton></Tooltip>
          </nav>
          <a className="breadcrumb-github" href="https://github.com/NJHTR/SeekCHA" target="_blank" rel="noopener noreferrer" aria-label="访问 SeekCHA GitHub 仓库" title="GitHub"><Github aria-hidden="true" /><span>GitHub</span></a>
        </header>

        <div className="page-scroll">
          {page === "overview" ? <OverviewPage time={time} scheduled={scheduled} scheduledTime={scheduledTime} scheduleDays={scheduleDays} records={records} successRate={recentSuccessRate} successCount={recentSuccessCount} isChecking={isChecking || checkQueue.active} queue={checkQueue} connected={runtimeMode === "connected"} authenticated={Boolean(user)} accountConfigured={accountConfigured} accountVerified={accountVerified} accountHint={accountHint} onRunCheck={runCheck} onNavigate={navigate} onSignIn={() => setAuthMode("login")} /> : null}
          {page === "history" && user ? <HistoryPage records={historyRecords} filter={recordFilter} page={historyPage} total={historyTotal} totalPages={historyPages} loading={historyLoading} onPageChange={setHistoryPage} onFilter={(filter) => { setRecordFilter(filter); setHistoryPage(1); }} onSelect={setSelectedRecord} onRefresh={() => setHistoryRevision((revision) => revision + 1)} /> : null}
          {page === "notices" && user ? <AcademicNoticesPage items={academicNotices} meta={academicNoticesMeta} loading={academicNoticesLoading} error={academicNoticesError} onRefresh={() => setAcademicNoticesRevision((revision) => revision + 1)} onConfigure={() => navigate("account")} /> : null}
          {page === "timetable" && user ? <AcademicSchedulePage items={academicSchedule} meta={academicScheduleMeta} loading={academicScheduleLoading} error={academicScheduleError} onRefresh={() => setAcademicScheduleRevision((revision) => revision + 1)} onConfigure={() => navigate("account")} /> : null}
          {page === "schedule" && user ? <SchedulePage scheduled={scheduled} scheduledTime={scheduledTime} scheduleDays={scheduleDays} onScheduledChange={setScheduled} onTimeChange={setScheduledTime} onDaysChange={setScheduleDays} onSave={saveSchedule} connected={runtimeMode === "connected"} accountConfigured={accountConfigured} accountVerified={accountVerified} /> : null}
          {page === "account" && user ? <AccountPage section={accountSection} onSectionChange={setAccountSection} accountHint={accountHint} configured={accountConfigured} verified={accountVerified} currentDeviceId={currentDeviceId} onNotice={setNotice} onSave={saveAccount} onNotificationsChanged={() => void refreshNotifications()} onVerify={async () => {
            if (runtimeMode !== "connected") { setNotice("演示模式无法连接校园平台验证账号"); return; }
            try { const result = await dormApi.verifyCredentials(); setAccountVerified(result.verified); setNotice(result.message); void refreshNotifications(); }
            catch (error) { setNotice(error instanceof Error ? error.message : "账号验证失败"); }
          }} connected={runtimeMode === "connected"} /> : null}
          {page === "admin" && adminRole ? <AdminPage role={adminRole} /> : null}
        </div>
        <footer className="site-footer">
          <span className="site-footer__credit">本系统由 <strong>@NJHTR</strong> 开发</span>
          <button type="button" className="site-footer__settings" onClick={() => setInterfaceSettingsOpen(true)}><Settings2 aria-hidden="true" /><span>界面设置</span><small>{Math.round(interfaceSettings.fontScale * 100)}%</small></button>
        </footer>
      </div>
      </WindowFrame>

      {notice ? <Notification title="查寝中心" icon={<CheckCheck />} duration={3200} onDismiss={() => setNotice("")}>{notice}</Notification> : null}
      <Dialog open={Boolean(authMode)} title={authMode === "register" ? "创建账户" : "登录查寝中心"} onClose={() => { setAuthError(""); setAuthMode(null); }} className="auth-dialog">
        {authMode ? <AuthPage mode={authMode} onModeChange={(mode) => { setAuthError(""); setAuthMode(mode); }} onSubmit={handleAuth} onSendCode={sendRegistrationCode} onNotice={setNotice} error={authError} allowDemoReturn={runtimeMode === "demo"} /> : null}
      </Dialog>
      <Dialog open={Boolean(selectedRecord)} title="查寝记录详情" description={runtimeMode === "demo" ? "演示记录，不代表服务器上的实际任务结果。" : "服务器任务的执行结果。"} onClose={() => setSelectedRecord(null)} footer={<Button variant="primary" onClick={() => setSelectedRecord(null)}>完成</Button>}>
        {selectedRecord ? <div className="record-detail"><DetailRow label="执行时间" value={selectedRecord.time} /><DetailRow label="结果" value={selectedRecord.status === "success" ? "成功" : "失败"} /><DetailRow label="任务消息" value={selectedRecord.message} /><DetailRow label="执行耗时" value={selectedRecord.duration} /></div> : null}
      </Dialog>
      <WorkspaceSettings open={interfaceSettingsOpen} value={interfaceSettings} onChange={setInterfaceSettings} onClose={() => setInterfaceSettingsOpen(false)} onReset={() => setInterfaceSettings(DEFAULT_INTERFACE_SETTINGS)} />
    </div>
  );
}

function NotificationCenter({ authenticated, items, unreadCount, loading, onRefresh, onMarkAllRead, onOpenNotification, onSignIn, onViewAll }: {
  authenticated: boolean; items: ApiNotification[]; unreadCount: number; loading: boolean; onRefresh: () => Promise<void>;
  onMarkAllRead: () => void; onOpenNotification: (item: ApiNotification) => void; onSignIn: () => void; onViewAll: () => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const closeOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === "Escape") setOpen(false); };
    document.addEventListener("pointerdown", closeOutside);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOutside);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [open]);

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next && authenticated) void onRefresh().catch(() => {});
  };

  return <div className="notification-center" ref={rootRef}>
    <Tooltip content="通知"><span className="notification-trigger-wrap"><IconButton label="通知" aria-haspopup="dialog" aria-expanded={open} onClick={toggle}><Bell /></IconButton>{unreadCount > 0 ? <span className="notification-unread-badge">{unreadCount > 99 ? "99+" : unreadCount}</span> : null}</span></Tooltip>
    {open ? <section className="notification-popover" role="dialog" aria-label="系统通知">
      <header className="notification-header"><div><strong>通知</strong><span>{authenticated ? `${unreadCount} 条未读` : "账户通知"}</span></div><Button size="small" variant="quiet" leadingIcon={<CheckCheck />} disabled={!authenticated || unreadCount === 0} onClick={onMarkAllRead}>全部已读</Button></header>
      {!authenticated ? <div className="notification-empty"><Bell /><strong>登录后查看系统通知</strong><Button size="small" variant="primary" onClick={onSignIn}>登录</Button></div> : loading && !items.length ? <div className="notification-empty"><span className="loading-dot" /><span>正在读取通知</span></div> : items.length ? <div className="notification-list">{items.map((item) => <button type="button" className={`notification-item ${item.read ? "" : "is-unread"}`} key={item.id} onClick={() => { setOpen(false); onOpenNotification(item); }}>
        <span className={`notification-mark ${item.status === "failure" ? "is-failure" : item.type === "run" ? "is-success" : "is-info"}`}>{item.status === "failure" ? <CircleAlert /> : item.type === "schedule" ? <CalendarClock /> : item.type === "credentials" ? <KeyRound /> : item.type === "security" ? <ShieldCheck /> : <CheckCheck />}</span>
        <span className="notification-copy"><span className="notification-title">{item.title || (item.type === "run" ? `查寝${item.status === "failure" ? "失败" : "成功"}` : "系统通知")}<time>{formatNotificationTime(item.time)}</time></span><span className="notification-message">{item.message}</span></span>
        {!item.read ? <i aria-label="未读" /> : null}
      </button>)}</div> : <div className="notification-empty"><Bell /><strong>暂无系统通知</strong><span>查寝结果、配置变更和安全事件会显示在这里。</span></div>}
      {authenticated ? <footer className="notification-footer"><Button size="small" variant="quiet" leadingIcon={<History />} onClick={() => { setOpen(false); onViewAll(); }}>查看查寝记录</Button></footer> : null}
    </section> : null}
  </div>;
}

function AdminPage({ role }: { role: "owner" | "operator" | "analyst" }) {
  const [overview, setOverview] = useState<ApiAdminOverview | null>(null);
  const [users, setUsers] = useState<ApiAdminUser[]>([]);
  const [userTotal, setUserTotal] = useState(0);
  const [userPage, setUserPage] = useState(1);
  const [userQuery, setUserQuery] = useState("");
  const [userSearchInput, setUserSearchInput] = useState("");
  const [runs, setRuns] = useState<ApiAdminRun[]>([]);
  const [queue, setQueue] = useState<ApiAdminQueueItem[]>([]);
  const [features, setFeatures] = useState<ApiAdminFeatureUse[]>([]);
  const [audit, setAudit] = useState<ApiAdminAudit[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [roleBusy, setRoleBusy] = useState("");
  const refresh = async () => {
    setLoading(true); setError("");
    try {
      const [summary, userRows, runRows, queueRows, featureRows, auditRows] = await Promise.all([
        dormApi.adminOverview(), role === "analyst" ? Promise.resolve({ items: [] as ApiAdminUser[], total: 0 }) : dormApi.adminUsers(userPage, userQuery),
        role === "analyst" ? Promise.resolve({ items: [] as ApiAdminRun[] }) : dormApi.adminRuns(),
        role === "analyst" ? Promise.resolve({ items: [] as ApiAdminQueueItem[] }) : dormApi.adminQueue(),
        role === "analyst" ? Promise.resolve({ items: [] as ApiAdminFeatureUse[] }) : dormApi.adminFeatures(),
        role === "owner" ? dormApi.adminAudit() : Promise.resolve({ items: [] as ApiAdminAudit[] }),
      ]);
      setOverview(summary); setUsers(userRows.items); setUserTotal(userRows.total); setRuns(runRows.items); setQueue(queueRows.items); setFeatures(featureRows.items); setAudit(auditRows.items);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法读取管理员数据"); }
    finally { setLoading(false); }
  };
  const changeRole = async (user: ApiAdminUser, role: "none" | "owner" | "operator" | "analyst") => {
    const reason = window.prompt(`设置 ${user.name} 的管理员角色。请填写操作原因：`);
    if (!reason?.trim()) return;
    setRoleBusy(user.id);
    try { await dormApi.adminSetRole(user.id, role, reason.trim()); await refresh(); }
    catch (reasonError) { setError(reasonError instanceof Error ? reasonError.message : "角色更新失败"); }
    finally { setRoleBusy(""); }
  };
  useEffect(() => { void refresh(); }, [role, userPage, userQuery]);
  if (loading && !overview) return <div className="page-content admin-page"><div className="admin-loading">正在读取运营数据…</div></div>;
  if (error && !overview) return <div className="page-content admin-page"><Notice title="管理数据暂不可用" tone="warning">{error}</Notice></div>;
  const stats = overview?.users;
  const today = overview?.runs_today;
  return <div className="page-content admin-page">
    <div className="page-heading"><div><div className="eyebrow">ADMINISTRATION</div><h1>管理后台</h1><p>只读运营视图 · 当前角色：{role === "owner" ? "所有者" : role === "operator" ? "运维管理员" : "分析员"}</p></div><Button size="small" leadingIcon={<RefreshCw />} onClick={() => void refresh()}>刷新数据</Button></div>
    {error ? <Notice title="部分数据读取失败" tone="warning">{error}</Notice> : null}
    <div className="stats-grid admin-stats">
      <StatCard label="注册用户" value={String(stats?.total ?? 0)} detail={`已配置认证 ${stats?.configured ?? 0} 人`} icon={<UserRound />} tone="green" />
      <StatCard label="已验证认证" value={String(stats?.verified ?? 0)} detail={`已启用定时 ${stats?.scheduled ?? 0} 人`} icon={<ShieldCheck />} tone="teal" />
      <StatCard label="今日查寝" value={String(today?.total ?? 0)} detail={`成功 ${today?.successes ?? 0} · 失败 ${today?.failures ?? 0}`} icon={<CheckCheck />} tone="blue" />
      <StatCard label="当前队列" value={String((overview?.queue.queued ?? 0) + (overview?.queue.running ?? 0) + (overview?.queue.retry_wait ?? 0))} detail={`等待 ${overview?.queue.queued ?? 0} · 执行 ${overview?.queue.running ?? 0}`} icon={<Activity />} tone="amber" />
    </div>
    {role !== "analyst" ? <><div className="admin-grid">
      <section className="surface-card admin-panel"><div className="section-heading"><div><span className="section-kicker">账户状态</span><h2>用户配置与权限</h2></div><form className="admin-search" onSubmit={(event) => { event.preventDefault(); setUserPage(1); setUserQuery(userSearchInput.trim()); }}><input aria-label="搜索用户邮箱或名称" placeholder="搜索邮箱或名称" value={userSearchInput} onChange={(event) => setUserSearchInput(event.target.value)} /><Button size="small" type="submit">搜索</Button></form></div><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>用户</th><th>校园认证</th><th>定时查寝</th><th>最近结果</th>{role === "owner" ? <th>后台角色</th> : null}</tr></thead><tbody>{users.map((item) => <tr key={item.id}><td><strong>{item.name}</strong><small>{item.email}</small></td><td><StatusBadge tone={item.credential_verified ? "success" : item.credential_configured ? "warning" : "neutral"} dot>{item.credential_verified ? "已验证" : item.credential_configured ? "已配置" : "未配置"}</StatusBadge></td><td>{item.schedule_enabled ? "已启用" : "已暂停"}</td><td>{item.last_run_status === "success" ? "成功" : item.last_run_status === "failure" ? "失败" : "暂无"}</td>{role === "owner" ? <td><select className="admin-role-select" aria-label={`设置 ${item.name} 的后台角色`} value={item.admin_role ?? "none"} disabled={roleBusy === item.id} onChange={(event) => void changeRole(item, event.target.value as "none" | "owner" | "operator" | "analyst")}><option value="none">无权限</option><option value="analyst">分析员</option><option value="operator">运维管理员</option><option value="owner">所有者</option></select></td> : null}</tr>)}</tbody></table></div><footer className="admin-pagination"><span>共 {userTotal} 位用户</span><Button size="small" variant="quiet" disabled={userPage <= 1} onClick={() => setUserPage((pageNumber) => pageNumber - 1)}>上一页</Button><span>{userPage} / {Math.max(1, Math.ceil(userTotal / 25))}</span><Button size="small" variant="quiet" disabled={userPage >= Math.ceil(userTotal / 25)} onClick={() => setUserPage((pageNumber) => pageNumber + 1)}>下一页</Button></footer></section>
      <section className="surface-card admin-panel"><div className="section-heading"><div><span className="section-kicker">任务调度</span><h2>当前队列</h2></div></div>{queue.length ? <div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>用户</th><th>来源</th><th>状态</th><th>尝试</th></tr></thead><tbody>{queue.slice(0, 12).map((item) => <tr key={item.id}><td><strong>{item.name}</strong><small>{item.email}</small></td><td>{item.trigger === "scheduled" ? "定时" : "手动"}</td><td><StatusBadge tone={item.status === "running" ? "success" : "warning"} dot>{item.status === "running" ? "执行中" : item.status === "retry_wait" ? "等待重试" : "排队中"}</StatusBadge></td><td>{item.attempts} / {item.max_attempts}</td></tr>)}</tbody></table></div> : <div className="admin-empty">当前没有等待中的任务</div>}</section>
    </div>
    <div className="admin-grid">
      <section className="surface-card admin-panel"><div className="section-heading"><div><span className="section-kicker">查寝记录</span><h2>最近执行结果</h2></div></div><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>用户</th><th>结果</th><th>原因</th><th>时间</th></tr></thead><tbody>{runs.slice(0, 12).map((item) => <tr key={item.id}><td><strong>{item.name}</strong><small>{item.email}</small></td><td><StatusBadge tone={item.status === "success" ? "success" : "danger"} dot>{item.status === "success" ? "成功" : "失败"}</StatusBadge></td><td className="admin-message">{item.message || "—"}</td><td>{formatNotificationTime(item.time)}</td></tr>)}</tbody></table></div></section>
      <section className="surface-card admin-panel"><div className="section-heading"><div><span className="section-kicker">功能使用</span><h2>课表与公告</h2></div></div><div className="admin-usage-summary"><div><strong>{overview?.feature_use_today.timetable ?? 0}</strong><span>今日课表访问</span></div><div><strong>{overview?.feature_use_today.notices ?? 0}</strong><span>今日公告访问</span></div></div><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>用户</th><th>功能</th><th>结果</th><th>时间</th></tr></thead><tbody>{features.slice(0, 8).map((item, index) => <tr key={`${item.time}-${item.email}-${index}`}><td><strong>{item.name}</strong><small>{item.email}</small></td><td>{item.feature === "schedule" ? "课表" : "公告"}</td><td>{item.result === "success" ? "成功" : "失败"}</td><td>{formatNotificationTime(item.time)}</td></tr>)}</tbody></table></div></section>
    </div>{role === "owner" ? <section className="surface-card admin-panel"><div className="section-heading"><div><span className="section-kicker">安全审计</span><h2>管理员访问与操作</h2></div></div><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>管理员</th><th>操作</th><th>来源地址</th><th>时间</th></tr></thead><tbody>{audit.slice(0, 20).map((item, index) => <tr key={`${item.time}-${index}`}><td>{item.admin || "管理员账号已不存在"}</td><td className="admin-message">{item.action}</td><td>{item.remote_address}</td><td>{formatNotificationTime(item.time)}</td></tr>)}</tbody></table></div></section> : null}</> : null}
  </div>;
}

function OverviewPage({ time, scheduled, scheduledTime, scheduleDays, records, successRate, successCount, isChecking, queue, connected, authenticated, accountConfigured, accountVerified, accountHint, onRunCheck, onNavigate, onSignIn }: { time: Date; scheduled: boolean; scheduledTime: string; scheduleDays: number[]; records: CheckRecord[]; successRate: number; successCount: number; isChecking: boolean; queue: ApiCheckQueue; connected: boolean; authenticated: boolean; accountConfigured: boolean; accountVerified: boolean; accountHint: string; onRunCheck: () => void; onNavigate: (page: Page) => void; onSignIn: () => void }) {
  const latest = records[0];
  return <div className="page-content overview-page">
    <div className="page-heading"><div><div className="eyebrow">{formatEyebrow(time)}</div><h1>查寝总览</h1><p>{formatDay(time)} <span className="live-clock"><Clock3 />{formatClock(time)}</span></p></div><Button variant="primary" leadingIcon={authenticated ? queue.active || isChecking ? <RefreshCw className="spin" /> : <Play /> : <KeyRound />} disabled={authenticated && (isChecking || queue.active)} onClick={() => authenticated ? onRunCheck() : onSignIn()}>{authenticated ? queue.active ? queue.status === "running" ? "正在执行" : "已排队" : isChecking ? "正在提交" : "立即查寝" : "登录或注册"}</Button></div>
    <div className="stats-grid">
      <StatCard label="最近一次查寝" value={!authenticated ? "未登录" : !latest ? "暂无记录" : latest.status === "success" ? "已完成" : "需处理"} detail={!authenticated ? "登录后查看记录" : latest?.time ?? "配置后开始自动查寝"} icon={<CheckCheck />} tone="green" />
      <StatCard label="近 7 日成功率" value={authenticated ? `${successRate}%` : "未登录"} detail={authenticated ? `${successCount} 次成功记录` : "登录后查看统计"} icon={<Activity />} tone="blue" />
      <StatCard label="下一次查寝" value={!authenticated ? "未登录" : scheduled ? scheduledTime : "已暂停"} detail={!authenticated ? "登录后配置任务" : scheduled ? `${formatScheduleDays(scheduleDays)} · 中国标准时间` : "定时任务未启用"} icon={<CalendarClock />} tone="amber" />
      <StatCard label="关联校园账号" value={!authenticated ? "未登录" : accountConfigured ? "已配置" : "未配置"} detail={!authenticated ? "登录后管理账号" : connected ? "凭据已加密托管" : "演示账号，不会保存密码"} icon={<ShieldCheck />} tone="teal" />
    </div>
    <div className="overview-grid">
      <section className="surface-card schedule-card">
        <div className="section-heading"><div><span className="section-kicker">自动执行</span><h2>定时查寝</h2></div><Button variant="quiet" size="small" leadingIcon={<ChevronDown />} onClick={() => authenticated ? onNavigate("schedule") : onSignIn()}>{authenticated ? "配置任务" : "登录后配置"}</Button></div>
        <div className="schedule-summary"><div className="schedule-clock">{authenticated ? scheduled ? scheduledTime : "--:--" : "未登录"}</div><StatusBadge tone={authenticated && scheduled ? "success" : "neutral"} dot>{authenticated ? scheduled ? "运行中" : "已暂停" : "未登录"}</StatusBadge></div>
        <div className="schedule-meta"><span><CalendarDays />{authenticated ? formatScheduleDays(scheduleDays) : "登录后查看"}</span><span><Clock3 />Asia/Shanghai (UTC+8)</span></div>
        <div className="schedule-next"><span>下次计划执行</span><strong>{authenticated ? scheduled ? `${nextRunDay(time, scheduledTime, scheduleDays)} ${scheduledTime}` : "任务已暂停" : "未登录"}</strong></div>
        <div className="week-strip" aria-label="每周执行日期">{displayWeekdays.map(({ value, short }) => <div key={value} className={`week-day ${authenticated && scheduleDays.includes(value) ? "is-scheduled" : ""}`}><span>{short}</span><i>{authenticated && scheduleDays.includes(value) ? <Check /> : "·"}</i></div>)}</div>
      </section>
      <section className="surface-card status-card">
        <div className="section-heading"><div><span className="section-kicker">账户状态</span><h2>校园连接</h2></div><ShieldCheck className="status-shield" /></div>
        <div className="connection-row"><div className="connection-icon"><DoorOpen /></div><div><strong>校园查寝平台</strong><span>{authenticated ? connected ? accountConfigured ? `账号 ${accountHint}` : "尚未配置账号凭据" : "演示账号 · 尚未连接服务端" : "未登录"}</span></div><StatusBadge tone={authenticated ? connected ? accountVerified ? "success" : "warning" : "info" : "neutral"} dot>{authenticated ? connected ? accountVerified ? "已验证" : accountConfigured ? "待验证" : "待配置" : "演示中" : "未登录"}</StatusBadge></div>
        <div className="connection-info"><span>最近验证</span><strong>{authenticated ? accountVerified ? "已验证" : "未验证" : "未登录"}</strong></div>
        <div className="connection-info"><span>通知方式</span><strong>{authenticated ? connected ? "任务状态记录" : "需连接服务端" : "未登录"}</strong></div>
        <Button size="small" leadingIcon={<Settings2 />} onClick={() => authenticated ? onNavigate("account") : onSignIn()}>{authenticated ? "管理账号" : "登录或注册"}</Button>
      </section>
    </div>
    <section className="surface-card recent-section">
      <div className="section-heading"><div><span className="section-kicker">执行记录</span><h2>最近查寝</h2></div><Button size="small" leadingIcon={<History />} onClick={() => onNavigate("history")}>查看全部</Button></div>
      {authenticated ? <RecordsTable records={records.slice(0, 4)} onSelect={() => onNavigate("history")} /> : <div className="empty-state"><LockKeyhole /><strong>未登录</strong><span>登录后查看查寝记录。</span><Button size="small" variant="quiet" leadingIcon={<KeyRound />} onClick={onSignIn}>登录或注册</Button></div>}
    </section>
    {!authenticated ? <div className="demo-footnote"><CircleHelp />当前未登录，不会显示账户、任务计划或历史数据。</div> : !connected ? <div className="demo-footnote"><CircleHelp />服务暂不可用，当前不会显示虚构的账户或查寝记录。</div> : null}
  </div>;
}

function StatCard({ label, value, detail, icon, tone }: { label: string; value: string; detail: string; icon: ReactNode; tone: string }) {
  return <section className="stat-card"><div className={`stat-icon ${tone}`}>{icon}</div><div className="stat-copy"><span>{label}</span><strong>{value}</strong><small>{detail}</small></div></section>;
}

function HistoryPage({ records, filter, page, total, totalPages, loading, onPageChange, onFilter, onSelect, onRefresh }: { records: CheckRecord[]; filter: "all" | "success" | "failure"; page: number; total: number; totalPages: number; loading: boolean; onPageChange: (page: number) => void; onFilter: (value: "all" | "success" | "failure") => void; onSelect: (record: CheckRecord) => void; onRefresh: () => void }) {
  return <div className="page-content">
    <div className="page-heading"><div><div className="eyebrow">ACTIVITY LOG</div><h1>查寝记录</h1><p>查看每次自动查寝的结果、耗时和错误详情。</p></div><Button leadingIcon={<RefreshCw />} disabled={loading} onClick={onRefresh}>刷新记录</Button></div>
    <section className="surface-card history-card" aria-busy={loading}>
      <div className="history-toolbar"><div className="history-count"><strong>{total}</strong><span>条任务记录</span></div><SegmentedControl label="按结果筛选" value={filter} onChange={onFilter} options={[{ value: "all", label: "全部" }, { value: "success", label: "成功" }, { value: "failure", label: "失败" }]} /></div>
      {loading && !records.length ? <div className="empty-state"><span className="loading-dot" /><strong>正在读取记录</strong></div> : records.length ? <RecordsTable records={records} onSelect={onSelect} /> : <div className="empty-state"><History /><strong>没有符合条件的记录</strong><span>调整筛选条件后再查看。</span></div>}
      {totalPages > 1 ? <div className="history-pagination"><span>第 {page} / {totalPages} 页</span><div><IconButton label="上一页" title="上一页" disabled={loading || page <= 1} onClick={() => onPageChange(page - 1)}><ChevronLeft /></IconButton><IconButton label="下一页" title="下一页" disabled={loading || page >= totalPages} onClick={() => onPageChange(page + 1)}><ChevronRight /></IconButton></div></div> : null}
    </section>
  </div>;
}

function academicCacheLabel(meta: ApiAcademicDataMeta | null) {
  if (!meta?.fetched_at) return "尚未同步";
  const time = new Date(meta.fetched_at).toLocaleString("zh-CN", { hour12: false });
  const status = meta.cache_status === "stale-fallback" ? " · 上游暂不可用，显示最近数据" : meta.cache_status === "stale" ? " · 后台更新中" : "";
  return `上次同步 ${time}${status}`;
}

function AcademicNoticesPage({ items, meta, loading, error, onRefresh, onConfigure }: { items: ApiAcademicNotice[]; meta: ApiAcademicDataMeta | null; loading: boolean; error: string; onRefresh: () => void; onConfigure: () => void }) {
  return <div className="page-content">
      <div className="page-heading"><div><div className="eyebrow">CAMPUS NOTICES</div><h1>教务公告</h1><p>融合门户中的教务通知。<span className="academic-sync-note">{academicCacheLabel(meta)}</span></p></div><Button leadingIcon={<RefreshCw />} disabled={loading} onClick={onRefresh}>刷新公告</Button></div>
    <section className="surface-card notices-card" aria-busy={loading}>
      {loading && !items.length ? <div className="empty-state"><span className="loading-dot" /><strong>正在读取公告</strong></div> : null}
      {!loading && error ? <div className="empty-state notices-error"><CircleAlert /><strong>公告暂不可用</strong><span>{error}</span><Button variant="quiet" size="small" onClick={onConfigure}>查看校园账号设置</Button><Button variant="quiet" size="small" leadingIcon={<RefreshCw />} onClick={onRefresh}>重试</Button></div> : null}
      {!loading && !error && !items.length ? <div className="empty-state"><Newspaper /><strong>暂无教务公告</strong></div> : null}
      {items.length ? <div className="notice-list">{items.map((item, index) => {
        let safeUrl = "";
        try {
          const parsed = new URL(item.url);
          if (parsed.protocol === "https:" && parsed.hostname === "jw.gzist.edu.cn") safeUrl = parsed.href;
        } catch { /* Ignore malformed remote links. */ }
        return <article className="notice-row" key={`${item.date}-${item.title}-${index}`}>
          <time dateTime={item.date}>{item.date}</time>
          {safeUrl ? <a href={safeUrl} target="_blank" rel="noopener noreferrer">{item.title}<ExternalLink aria-hidden="true" /></a> : <span className="notice-title">{item.title}</span>}
        </article>;
      })}</div> : null}
    </section>
  </div>;
}

type TimetableView = "week" | "month" | "all";
type TimetableCourse = ApiAcademicScheduleItem & { day: number; start: number; span: number; weeksNumbers: number[] };

const timetableDays = [
  { value: 7, label: "星期日", short: "日" },
  { value: 1, label: "星期一", short: "一" },
  { value: 2, label: "星期二", short: "二" },
  { value: 3, label: "星期三", short: "三" },
  { value: 4, label: "星期四", short: "四" },
  { value: 5, label: "星期五", short: "五" },
  { value: 6, label: "星期六", short: "六" },
];
const timetablePeriods = [
  [1, "08:20–09:05"], [2, "09:15–10:00"], [3, "10:20–11:05"], [4, "11:15–12:00"],
  [5, "14:00–14:45"], [6, "14:55–15:40"], [7, "16:00–16:45"], [8, "16:55–17:40"],
  [9, "18:40–19:25"], [10, "19:25–20:00"], [11, "20:20–21:05"],
] as const;

function timetableDay(value: string) {
  const normalized = value.trim();
  const chineseDay = ["日", "一", "二", "三", "四", "五", "六"].findIndex((day) => normalized.includes(`星期${day}`) || normalized.includes(`周${day}`));
  if (chineseDay >= 0) return chineseDay === 0 ? 7 : chineseDay;
  const match = normalized.match(/[1-7]/);
  return match ? Number(match[0]) : 1;
}

function timetableWeeks(value: string) {
  const weeks: number[] = [];
  for (const part of value.match(/\d+\s*(?:-|至)\s*\d+|\d+/g) ?? []) {
    const numbers = part.match(/\d+/g)?.map(Number) ?? [];
    if (numbers.length === 2) for (let week = numbers[0]; week <= numbers[1]; week += 1) weeks.push(week);
    else if (numbers[0]) weeks.push(numbers[0]);
  }
  return [...new Set(weeks)].sort((a, b) => a - b);
}

function academicTermStart(now = new Date()) {
  // January still belongs to the autumn term that began the previous year.
  const month = now.getMonth();
  const autumn = month >= 7 || month === 0;
  const termYear = month === 0 ? now.getFullYear() - 1 : now.getFullYear();
  const start = autumn
    ? new Date(termYear, 8, 1)
    : new Date(now.getFullYear(), 1, 1);
  while (start.getDay() !== 1) start.setDate(start.getDate() + 1);
  return start;
}

function currentAcademicWeek() {
  const now = new Date();
  const start = academicTermStart(now);
  return Math.max(1, Math.floor((now.getTime() - start.getTime()) / 604800000) + 1);
}

function timetableDateLabel(date: Date) {
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`;
}

function timetableDateForDay(termStart: Date, week: number, day: number) {
  const date = new Date(termStart);
  date.setDate(termStart.getDate() + (week - 1) * 7 + (day === 7 ? 6 : day - 1));
  return date;
}

function timetableWeekRangeLabel(termStart: Date, week: number) {
  const start = timetableDateForDay(termStart, week, 1);
  const end = timetableDateForDay(termStart, week, 7);
  return `${timetableDateLabel(start)} – ${timetableDateLabel(end)}`;
}

function normalizeTimetable(items: ApiAcademicScheduleItem[]): TimetableCourse[] {
  return items.flatMap((item) => {
    const periods = item.periods.match(/\d+/g)?.map(Number) ?? [];
    const start = periods[0] ?? 1;
    const end = periods.length > 1 ? periods[periods.length - 1] : start;
    return [{ ...item, day: timetableDay(item.weekday), start, span: Math.max(1, Math.min(timetablePeriods.length, end - start + 1)), weeksNumbers: timetableWeeks(item.weeks) }];
  });
}

function TimetableCourseCard({ course, compact = false }: { course: TimetableCourse; compact?: boolean }) {
  const practice = course.type.includes("集中");
  return <div className={`timetable-course timetable-course--${practice ? "practice" : "normal"} ${compact ? "is-compact" : ""}`} title={`${course.course} · ${course.room || "待定教室"} · ${course.teacher || "待定教师"}`}>
    <strong className="timetable-course-title">{course.course}</strong>
    <div className="timetable-course-meta"><span>{course.room || "待定教室"}</span><span>{course.periods}节</span></div>
    {!compact ? <><small className="timetable-course-teacher">{course.teacher || "待定教师"}</small><em className="timetable-course-weeks">{course.weeks || "全周"}</em></> : null}
  </div>;
}

function WeekTimetable({ courses, week, termStart }: { courses: TimetableCourse[]; week: number; termStart: Date }) {
  const visible = courses.filter((course) => !course.weeksNumbers.length || course.weeksNumbers.includes(week));
  const today = new Date();
  const todayDay = today.getDay() || 7;
  const isCurrentWeek = week === currentAcademicWeek();
  return <div className="timetable-grid-wrap"><div className="timetable-grid timetable-grid--week">
    <div className="timetable-corner"><strong>节次</strong><span>时间</span></div>
    {timetableDays.map((day) => { const date = timetableDateForDay(termStart, week, day.value); const todayColumn = isCurrentWeek && day.value === todayDay; return <div className={`timetable-day-head ${todayColumn ? "is-today" : ""}`} key={day.value}><strong>{day.label}</strong><time>{timetableDateLabel(date)}</time><span>{todayColumn ? "今天" : day.value === 7 || day.value === 6 ? "周末补课" : "工作日"}</span></div>; })}
    <div className="timetable-periods">{timetablePeriods.map(([period, time]) => <div className={`timetable-period timetable-period--${period}`} key={period}>{period === 1 || period === 5 || period === 9 ? <em>{period === 1 ? "上午" : period === 5 ? "下午" : "晚上"}</em> : null}<strong>{period}</strong><span>{time}</span></div>)}</div>
    {timetableDays.map((day) => <div className="timetable-day-column" key={day.value}>
      {timetablePeriods.map(([period]) => <div className="timetable-slot" key={period} />)}
      {visible.filter((course) => course.day === day.value).map((course, index) => <div className={`timetable-course-position ${course.span === 1 ? "is-short" : course.span === 2 ? "is-medium" : ""}`} key={`${course.course}-${course.start}-${course.weeks}-${index}`} style={{ top: `calc(${course.start - 1} * var(--timetable-slot-height) + 4px)`, height: `calc(${course.span} * var(--timetable-slot-height) - 8px)` }}><TimetableCourseCard course={course} /></div>)}
    </div>)}
  </div></div>;
}

function MonthTimetable({ courses, monthDate, termStart, maxWeek }: { courses: TimetableCourse[]; monthDate: Date; termStart: Date; maxWeek: number }) {
  const first = new Date(monthDate.getFullYear(), monthDate.getMonth(), 1);
  const today = new Date();
  const gridStart = new Date(first);
  gridStart.setDate(first.getDate() - first.getDay());
  const days = Array.from({ length: 42 }, (_, index) => {
    const date = new Date(gridStart);
    date.setDate(gridStart.getDate() + index);
    const week = Math.floor((date.getTime() - termStart.getTime()) / 604800000) + 1;
    const inTerm = week >= 1 && week <= maxWeek;
    const weekday = date.getDay() || 7;
    const visible = inTerm ? courses.filter((course) => course.day === weekday && (!course.weeksNumbers.length || course.weeksNumbers.includes(week))) : [];
    return { date, week, inTerm, visible };
  });
  return <div className="timetable-month-wrap"><div className="timetable-month-grid">
    {timetableDays.map((day) => <div className="timetable-month-weekday" key={day.value}>{day.label}</div>)}
    {days.map(({ date, week, inTerm, visible }) => <section className={`timetable-month-day ${date.getMonth() !== monthDate.getMonth() ? "is-outside" : ""} ${!inTerm ? "is-out-of-term" : ""} ${date.toDateString() === today.toDateString() ? "is-today" : ""}`} key={date.toISOString()}>
      <header><strong>{date.getDate()}</strong>{inTerm ? <span>第{week}周</span> : null}</header>
      <div>{visible.map((course, index) => <article className={`timetable-month-event ${course.type.includes("集中") ? "is-practice" : ""}`} key={`${course.course}-${course.start}-${index}`} title={`${course.course} · ${course.periods}节 · ${course.room || "待定教室"}`}><time>{course.periods}节</time><strong>{course.course}</strong><small>{course.room || "待定教室"}</small></article>)}</div>
    </section>)}
  </div></div>;
}

function AcademicSchedulePage({ items, meta, loading, error, onRefresh, onConfigure }: { items: ApiAcademicScheduleItem[]; meta: ApiAcademicDataMeta | null; loading: boolean; error: string; onRefresh: () => void; onConfigure: () => void }) {
  const courses = normalizeTimetable(items);
  const weeks = [...new Set(courses.flatMap((course) => course.weeksNumbers))].sort((a, b) => a - b);
  const maxWeek = weeks.length ? weeks[weeks.length - 1] : currentAcademicWeek();
  const availableWeeks = Array.from({ length: maxWeek }, (_, index) => index + 1);
  const [view, setView] = useState<TimetableView>("week");
  const [week, setWeek] = useState(() => currentAcademicWeek());
  const [monthDate, setMonthDate] = useState(() => new Date());
  const selectedWeek = availableWeeks.includes(week) ? week : availableWeeks[Math.min(availableWeeks.length - 1, currentAcademicWeek() - 1)] ?? 1;
  const termStart = academicTermStart();
  const weekRangeLabel = timetableWeekRangeLabel(termStart, selectedWeek);
  const minMonth = new Date(termStart.getFullYear(), termStart.getMonth(), 1);
  const maxTermDate = new Date(termStart);
  maxTermDate.setDate(termStart.getDate() + maxWeek * 7 - 1);
  const maxMonth = new Date(maxTermDate.getFullYear(), maxTermDate.getMonth(), 1);
  const canGoPreviousMonth = monthDate.getFullYear() > minMonth.getFullYear() || (monthDate.getFullYear() === minMonth.getFullYear() && monthDate.getMonth() > minMonth.getMonth());
  const canGoNextMonth = monthDate.getFullYear() < maxMonth.getFullYear() || (monthDate.getFullYear() === maxMonth.getFullYear() && monthDate.getMonth() < maxMonth.getMonth());
  const jumpToday = () => { setWeek(Math.min(currentAcademicWeek(), maxWeek)); setMonthDate(new Date()); setView("week"); };
  return <div className="page-content">
    <div className="page-heading timetable-heading"><div><div className="eyebrow">MY TIMETABLE</div><h1>我的课表</h1><p>周末补课、分周课程和集中实践课都会按教务系统原始周次展示。<span className="academic-sync-note">{academicCacheLabel(meta)}</span></p></div><div className="timetable-heading-actions"><Button variant="quiet" leadingIcon={<CalendarDays />} onClick={jumpToday}>今天</Button><Button leadingIcon={<RefreshCw />} disabled={loading} onClick={onRefresh}>刷新课表</Button></div></div>
    <section className="surface-card timetable-card" aria-busy={loading}>
      {loading && !items.length ? <div className="empty-state"><span className="loading-dot" /><strong>正在读取课表</strong></div> : null}
      {!loading && error ? <div className="empty-state notices-error"><CircleAlert /><strong>课表暂不可用</strong><span>{error}</span><Button variant="quiet" size="small" onClick={onConfigure}>查看校园账号设置</Button><Button variant="quiet" size="small" leadingIcon={<RefreshCw />} onClick={onRefresh}>重试</Button></div> : null}
      {!loading && !error && !items.length ? <div className="empty-state"><CalendarDays /><strong>暂无课表数据</strong></div> : null}
      {items.length ? <>
        <div className="timetable-toolbar"><SegmentedControl label="课表视图" value={view} onChange={(value) => setView(value as TimetableView)} options={[{ value: "week", label: "周视图" }, { value: "month", label: "月视图" }, { value: "all", label: "全部课程" }]} />
          <div className="timetable-range">{view === "week" ? <><Button variant="quiet" size="small" aria-label="上一周" disabled={selectedWeek <= 1} onClick={() => setWeek(Math.max(1, selectedWeek - 1))}><ChevronLeft /></Button><strong><span>第 {selectedWeek} 周</span><small>{weekRangeLabel}</small></strong><Button variant="quiet" size="small" aria-label="下一周" disabled={selectedWeek >= maxWeek} onClick={() => setWeek(Math.min(maxWeek, selectedWeek + 1))}><ChevronRight /></Button></> : view === "month" ? <><Button variant="quiet" size="small" aria-label="上一个月" disabled={!canGoPreviousMonth} onClick={() => setMonthDate((date) => new Date(date.getFullYear(), date.getMonth() - 1, 1))}><ChevronLeft /></Button><strong>{new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "long" }).format(monthDate)}</strong><Button variant="quiet" size="small" aria-label="下一个月" disabled={!canGoNextMonth} onClick={() => setMonthDate((date) => new Date(date.getFullYear(), date.getMonth() + 1, 1))}><ChevronRight /></Button></> : <strong>{items.length} 条课程安排</strong>}</div>
        </div>
        {view === "week" ? <WeekTimetable courses={courses} week={selectedWeek} termStart={termStart} /> : null}
        {view === "month" ? <MonthTimetable courses={courses} monthDate={monthDate} termStart={termStart} maxWeek={maxWeek} /> : null}
        {view === "all" ? <div className="table-scroll"><table className="records-table timetable-table"><thead><tr><th>课程</th><th>星期</th><th>节次</th><th>教室</th><th>教师</th><th>周次</th></tr></thead><tbody>{items.map((item, index) => <tr key={`${item.course}-${item.weekday}-${item.periods}-${index}`}><td><strong>{item.course}</strong>{item.type ? <small>{item.type}</small> : null}</td><td>{item.weekday || "-"}</td><td>{item.periods || "-"}</td><td>{item.room || "-"}</td><td>{item.teacher || "-"}</td><td>{item.weeks || "-"}</td></tr>)}</tbody></table></div> : null}
        <div className="timetable-legend"><span><i className="legend-dot legend-dot--normal" />常规课程</span><span><i className="legend-dot legend-dot--practice" />集中实践课</span><span><i className="legend-dot legend-dot--weekend" />周末/补课日照常显示</span></div>
      </> : null}
    </section>
  </div>;
}

function RecordsTable({ records, onSelect }: { records: CheckRecord[]; onSelect: (record: CheckRecord) => void }) {
  if (!records.length) return <div className="empty-state"><History /><strong>暂无查寝记录</strong></div>;
  return <div className="table-scroll"><table className="records-table"><thead><tr><th>执行时间</th><th>执行结果</th><th>任务消息</th><th>耗时</th><th aria-label="操作" /></tr></thead><tbody>{records.map((record) => <tr key={record.id} onClick={() => onSelect(record)}><td><strong>{record.time.slice(0, 10)}</strong><small>{record.time.slice(11)}</small></td><td><StatusBadge tone={record.status === "success" ? "success" : "danger"} dot>{record.status === "success" ? "成功" : "失败"}</StatusBadge></td><td className="message-cell">{record.message}</td><td>{record.duration}</td><td><IconButton label={`查看 ${record.time} 的记录详情`} size="small" onClick={(event) => { event.stopPropagation(); onSelect(record); }}><ChevronDown className="rotate-minus-90" /></IconButton></td></tr>)}</tbody></table></div>;
}

function SchedulePage({ scheduled, scheduledTime, scheduleDays, onScheduledChange, onTimeChange, onDaysChange, onSave, connected, accountConfigured, accountVerified }: { scheduled: boolean; scheduledTime: string; scheduleDays: number[]; onScheduledChange: (value: boolean) => void; onTimeChange: (value: string) => void; onDaysChange: (value: number[]) => void; onSave: () => void; connected: boolean; accountConfigured: boolean; accountVerified: boolean }) {
  const [hour = "21", minute = "40"] = scheduledTime.split(":");
  const daily = scheduleDays.length === 7;
  const setFrequency = (value: string) => {
    if (value === "daily") onDaysChange([1, 2, 3, 4, 5, 6, 7]);
    else if (daily) onDaysChange([1, 2, 3, 4, 5]);
  };
  const toggleDay = (day: number) => {
    if (scheduleDays.includes(day)) {
      if (scheduleDays.length > 1) onDaysChange(scheduleDays.filter((item) => item !== day));
      else onDaysChange(scheduleDays);
    } else onDaysChange([...scheduleDays, day].sort((a, b) => a - b));
  };
  return <div className="page-content page-content--settings">
    <div className="page-heading"><div><div className="eyebrow">AUTOMATION</div><h1>定时任务</h1><p>配置查寝脚本的自动运行时间和失败提醒。</p></div><Button variant="primary" leadingIcon={<Save />} onClick={onSave}>保存配置</Button></div>
    <section className="surface-card settings-card"><div className="section-heading"><div><span className="section-kicker">执行计划</span><h2>每日查寝</h2></div><StatusBadge tone={scheduled ? "success" : "neutral"} dot>{scheduled ? "已启用" : "已暂停"}</StatusBadge></div>
      <div className="setting-row"><div><strong>启用定时查寝</strong><span>按照计划自动登录校园平台并提交查寝</span></div><Switch label="" aria-label="启用定时查寝" checked={scheduled} onChange={(event) => onScheduledChange(event.target.checked)} /></div>
      <div className="setting-row setting-row--frequency"><div><strong>执行频率</strong><span>每天执行，或选择每周的一个或多个日期</span></div><div className="setting-control"><SegmentedControl label="执行频率" value={daily ? "daily" : "weekly"} onChange={setFrequency} options={[{ value: "daily", label: "每天" }, { value: "weekly", label: "每周指定日期" }]} /></div></div>
      {!daily ? <div className="setting-row setting-row--days"><div><strong>执行日期</strong><span>至少选择一个日期，可单选或多选</span></div><div className="weekday-picker" role="group" aria-label="每周执行日期">{displayWeekdays.map(({ value, short, label }) => <button key={value} type="button" className={`weekday-option ${scheduleDays.includes(value) ? "is-selected" : ""}`} aria-label={label} aria-pressed={scheduleDays.includes(value)} onClick={() => toggleDay(value)}>{short}</button>)}</div></div> : null}
      <div className="setting-row setting-row--time"><div><strong>查寝时间</strong><span>服务器按 Asia/Shanghai 时区调度</span></div><div className="time-picker"><label><span className="sr-only">小时</span><span className="time-select"><select aria-label="查寝小时" value={hour} onChange={(event) => onTimeChange(`${event.target.value}:${minute}`)}>{Array.from({ length: 24 }, (_, value) => String(value).padStart(2, "0")).map((value) => <option key={value} value={value}>{value}</option>)}</select><ChevronDown aria-hidden="true" /></span><small>时</small></label><b>:</b><label><span className="sr-only">分钟</span><span className="time-select"><select aria-label="查寝分钟" value={minute} onChange={(event) => onTimeChange(`${hour}:${event.target.value}`)}>{Array.from({ length: 60 }, (_, value) => String(value).padStart(2, "0")).map((value) => <option key={value} value={value}>{value}</option>)}</select><ChevronDown aria-hidden="true" /></span><small>分</small></label></div></div>
    </section>
    <section className="surface-card schedule-note"><div className="note-icon"><ShieldCheck /></div><div><strong>{connected ? "任务运行在服务器" : "演示环境"}</strong><span>{connected ? accountVerified ? "服务器将按 Asia/Shanghai 时区运行任务。校园平台账号未在浏览器中保存。" : accountConfigured ? "验证校园平台账号后，才能启用定时查寝。" : "先保存并验证校园平台账号，才能启用定时查寝。" : "当前没有连接查寝服务，保存的配置只保留在此演示界面。"}</span></div></section>
  </div>;
}

function AccountPage({ section, onSectionChange, accountHint, configured, verified, currentDeviceId, onNotice, onSave, onNotificationsChanged, onVerify, connected }: { section: "campus" | "security" | "devices"; onSectionChange: (section: "campus" | "security" | "devices") => void; accountHint: string; configured: boolean; verified: boolean; currentDeviceId: string; onNotice: (message: string) => void; onSave: (account: string, password: string) => void; onNotificationsChanged: () => void; onVerify: () => void; connected: boolean }) {
  const [account, setAccount] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    onSave(account, password);
    setPassword("");
  };
  return <div className="page-content page-content--settings account-page">
    <div className="page-heading"><div><div className="eyebrow">CONNECTED ACCOUNT</div><h1>账号设置</h1><p>管理校园平台账号、安全验证和登录设备。</p></div></div>
    <nav className="account-section-tabs" aria-label="账号设置分区">
      <button type="button" className={section === "campus" ? "is-active" : ""} onClick={() => onSectionChange("campus")}><KeyRound />校园平台</button>
      <button type="button" className={section === "security" ? "is-active" : ""} onClick={() => onSectionChange("security")}><ShieldCheck />二次认证</button>
      <button type="button" className={section === "devices" ? "is-active" : ""} onClick={() => onSectionChange("devices")}><UserRound />登录设备</button>
    </nav>
    <div className="account-settings-content">
    {section === "campus" ? <section className="surface-card account-form"><div className="section-heading"><div><span className="section-kicker">校园平台</span><h2>查寝账号</h2></div><StatusBadge tone={verified ? "success" : configured ? "warning" : "neutral"} dot>{verified ? "已验证" : configured ? "待验证" : "未配置"}</StatusBadge></div>
      <form onSubmit={submit}>
        {configured ? <div className="saved-account"><ShieldCheck /><span>已保存账号 <strong>{accountHint}</strong>。为保护账号，服务器不会返回完整凭据。</span></div> : null}
        <div className="account-form-grid"><TextField label="校园统一身份认证账号" value={account} onChange={(event) => setAccount(event.target.value)} placeholder={configured ? "输入新账号以替换" : "输入校园平台账号"} autoComplete="username" required /><TextField label="校园平台密码" type={showPassword ? "text" : "password"} value={password} onChange={(event) => setPassword(event.target.value)} placeholder="仅在提交时发送至本服务" autoComplete="new-password" required trailingAction={<IconButton type="button" label={showPassword ? "隐藏密码" : "显示密码"} size="small" onClick={() => setShowPassword((value) => !value)}>{showPassword ? <EyeOff /> : <Eye />}</IconButton>} /></div>
        <div className="security-callout"><ShieldCheck /><div><strong>{connected ? "凭据安全" : "演示环境保护"}</strong><span>{connected ? "账号和密码在服务端使用应用密钥加密保存，页面仅显示脱敏账号。" : "未连接服务端；演示模式不会发送信息或保存校园账号密码。"}</span></div></div>
        <div className="account-actions"><Button type="submit" variant="primary" leadingIcon={<Save />}>{configured ? "更新账号凭据" : "保存账号凭据"}</Button>{configured && connected ? <Button type="button" leadingIcon={<ShieldCheck />} onClick={onVerify}>验证校园账号</Button> : null}<span>{configured ? verified ? "凭据验证通过" : "验证通过后才能开启定时任务" : "建议使用专用校园账号"}</span></div>
      </form>
    </section> : null}
    {section === "security" ? connected ? <TotpSettings onNotificationsChanged={onNotificationsChanged} /> : <section className="surface-card account-unavailable"><ShieldCheck /><div><strong>安全验证暂不可用</strong><span>连接服务后可配置验证器二次认证。</span></div></section> : null}
    {section === "devices" ? connected ? <DeviceSettings currentDeviceId={currentDeviceId} onNotice={onNotice} onNotificationsChanged={onNotificationsChanged} /> : <section className="surface-card account-unavailable"><UserRound /><div><strong>登录设备暂不可用</strong><span>连接服务后可查看和管理登录设备。</span></div></section> : null}
    </div>
  </div>;
}

function DeviceSettings({ currentDeviceId, onNotice, onNotificationsChanged }: { currentDeviceId: string; onNotice: (message: string) => void; onNotificationsChanged: () => void }) {
  const [devices, setDevices] = useState<ApiDevice[]>([]);
  const [error, setError] = useState("");
  useEffect(() => { dormApi.devices().then((items) => setDevices(items.map((item) => ({ ...item, current: item.id === currentDeviceId })))).catch(() => setError("无法读取登录设备")); }, [currentDeviceId]);
  const revoke = async (device: ApiDevice) => {
    try {
      await dormApi.revokeDevice(device.id);
      setDevices((items) => items.filter((item) => item.id !== device.id));
      onNotice(device.current ? "已退出此设备" : "已撤销该设备的登录状态");
      onNotificationsChanged();
      if (device.current) window.location.reload();
    } catch (cause) { setError(cause instanceof Error ? cause.message : "无法撤销设备"); }
  };
  return <section className="surface-card device-settings">
    <div className="section-heading"><div><span className="section-kicker">账户安全</span><h2>登录设备</h2></div><StatusBadge tone="neutral">{devices.length} 台</StatusBadge></div>
    <p className="device-description">查看最近登录过的浏览器设备。设备信息用于识别会话，可随时撤销；它不是防伪身份凭据。</p>
    {error ? <Notice title="设备列表不可用" tone="warning">{error}</Notice> : null}
    <div className="device-list">{devices.map((device) => <div className="device-row" key={device.id}>
      <div className="device-symbol"><ShieldCheck /></div><div className="device-copy"><strong>{device.name}{device.current ? " · 当前设备" : ""}</strong><span>{[device.platform, device.screen_size, device.hardware_threads ? `${device.hardware_threads} 核` : "", device.device_memory ? `${device.device_memory} GB 内存` : "", device.timezone].filter(Boolean).join(" · ")}</span><small>登录时间 {new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(device.last_seen_at))}</small></div>
      <Button size="small" variant="quiet" onClick={() => void revoke(device)}>{device.current ? "退出此设备" : "撤销"}</Button>
    </div>)}</div>
  </section>;
}

function TotpSettings({ onNotificationsChanged }: { onNotificationsChanged: () => void }) {
  const [enabled, setEnabled] = useState(false);
  const [secret, setSecret] = useState("");
  const [code, setCode] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => { dormApi.totpStatus().then((status) => setEnabled(status.enabled)).catch(() => setMessage("无法读取二次认证状态")); }, []);
  const begin = async () => {
    setBusy(true); setMessage("");
    try { const setup = await dormApi.beginTotp(); setSecret(setup.secret); }
    catch (error) { setMessage(error instanceof Error ? error.message : "无法开始绑定"); }
    finally { setBusy(false); }
  };
  const confirm = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setMessage("");
    try { await dormApi.confirmTotp(code); setEnabled(true); setSecret(""); setCode(""); setMessage("验证器二次认证已启用"); onNotificationsChanged(); }
    catch (error) { setMessage(error instanceof Error ? error.message : "动态口令验证失败"); }
    finally { setBusy(false); }
  };
  return <section className="surface-card auth-security-card">
    <div className="section-heading"><div><span className="section-kicker">账户安全</span><h2>验证器二次认证</h2></div><StatusBadge tone={enabled ? "success" : "neutral"} dot>{enabled ? "已启用" : "未启用"}</StatusBadge></div>
    {enabled ? <div className="saved-account"><ShieldCheck /><span>登录时除邮箱验证码外，还需要验证器应用中的动态口令。</span></div> : <p className="security-description">使用兼容 TOTP 的验证器应用绑定账户。启用后，每次登录都需要动态口令。</p>}
    {secret ? <form className="totp-setup" onSubmit={confirm}><div><span>在验证器应用中手动添加密钥</span><code>{secret}</code></div><TextField label="验证器 6 位动态口令" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(event) => setCode(event.target.value)} maxLength={6} required /><Button type="submit" variant="primary" disabled={busy || code.length !== 6}>确认并启用</Button></form> : null}
    {message ? <div className="auth-success" role="status">{message}</div> : null}
    {!enabled && !secret ? <Button type="button" leadingIcon={<ShieldCheck />} disabled={busy} onClick={begin}>绑定验证器</Button> : null}
  </section>;
}

type AuthOutcome = { complete: true; user: ApiUser | null } | { complete: false; email: string; totpRequired: boolean };

function AuthPage({ mode, onModeChange, onSubmit, onSendCode, onNotice, error, allowDemoReturn }: { mode: AuthMode; onModeChange: (mode: AuthMode | null) => void; onSubmit: (values: { name: string; email: string; password: string; emailCode: string; totpCode: string; phase: "credentials" | "verification" }) => Promise<AuthOutcome>; onSendCode: (email: string) => Promise<void>; onNotice: (message: string) => void; error: string; allowDemoReturn: boolean }) {
  const [showPassword, setShowPassword] = useState(false);
  const [accepted, setAccepted] = useState(false);
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [emailCode, setEmailCode] = useState("");
  const [totpCode, setTotpCode] = useState("");
  const [step, setStep] = useState<"credentials" | "verification">("credentials");
  const [emailSent, setEmailSent] = useState(false);
  const [totpRequired, setTotpRequired] = useState(false);
  const [busy, setBusy] = useState(false);
  const [localError, setLocalError] = useState("");
  const [recoveryNotice, setRecoveryNotice] = useState(false);

  const requestCode = async () => {
    setLocalError("");
    if (!email) { setLocalError("请填写邮箱"); return; }
    setBusy(true);
    try {
      await onSendCode(email);
      setEmailSent(true);
      setEmailCode("");
      setStep("verification");
      onNotice(`验证码已发送至 ${email}`);
    } catch (cause) {
      setLocalError(cause instanceof Error ? cause.message : "验证码发送失败");
    } finally { setBusy(false); }
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLocalError("");
    if (mode === "register" && step === "credentials") {
      await requestCode();
      return;
    }
    setBusy(true);
    try {
      const outcome = await onSubmit({ name, email, password, emailCode, totpCode, phase: step });
      if (!outcome.complete) {
        setEmail(outcome.email);
        setTotpRequired(outcome.totpRequired);
        setStep("verification");
        setEmailCode("");
      }
    } catch (cause) {
      setLocalError(cause instanceof Error ? cause.message : "认证失败，请重试");
    } finally { setBusy(false); }
  };
  return (
    <div className="auth-form-wrap">
      <div className="auth-form-heading">
        <span className="section-kicker">{mode === "login" ? "WELCOME BACK" : "GET STARTED"}</span>
        <h2>{mode === "register" ? step === "verification" ? "验证邮箱" : "创建账户" : step === "verification" ? "完成二次认证" : "登录查寝中心"}</h2>
        <p>{mode === "register" ? step === "verification" ? `输入发送到 ${email} 的 6 位验证码。` : "先填写账户信息，再验证邮箱完成注册。" : step === "verification" ? `验证码已发送至 ${email}` : "通过邮箱验证后登录。"}</p>
      </div>
      {mode === "register" ? <div className="auth-steps" aria-label="注册步骤"><span className={step === "credentials" ? "is-active" : "is-complete"}><i>1</i>账户信息</span><span className="auth-steps-line" /><span className={step === "verification" ? "is-active" : ""}><i>2</i>邮箱验证</span></div> : null}
      <form className="auth-form" onSubmit={submit}>
            {mode === "register" && step === "credentials" ? <TextField label="姓名" autoComplete="name" placeholder="你的称呼" value={name} onChange={(event) => setName(event.target.value)} required /> : null}
            {step === "credentials" ? <TextField label="邮箱" type="email" autoComplete="email" placeholder="name@example.com" value={email} onChange={(event) => { setEmail(event.target.value); setEmailSent(false); }} required /> : null}
            {step === "credentials" ? <TextField label="密码" type={showPassword ? "text" : "password"} autoComplete={mode === "login" ? "current-password" : "new-password"} placeholder="至少 12 位字符" value={password} onChange={(event) => setPassword(event.target.value)} minLength={12} maxLength={256} required trailingAction={<IconButton type="button" label={showPassword ? "隐藏密码" : "显示密码"} size="small" onClick={() => setShowPassword((value) => !value)}>{showPassword ? <EyeOff /> : <Eye />}</IconButton>} /> : null}
            {mode === "register" && step === "verification" ? <TextField label="邮箱验证码" inputMode="numeric" autoComplete="one-time-code" value={emailCode} onChange={(event) => setEmailCode(event.target.value)} placeholder="6 位验证码" maxLength={6} required /> : null}
            {mode === "login" && step === "verification" ? <TextField label="邮箱验证码" inputMode="numeric" autoComplete="one-time-code" value={emailCode} onChange={(event) => setEmailCode(event.target.value)} placeholder="6 位验证码" maxLength={6} required /> : null}
            {step === "verification" && totpRequired ? <TextField label="验证器动态口令" inputMode="numeric" autoComplete="one-time-code" value={totpCode} onChange={(event) => setTotpCode(event.target.value)} placeholder="6 位动态口令" maxLength={6} required /> : null}
            {mode === "register" && step === "credentials" ? <Checkbox className="terms-check" label="我已阅读并同意服务条款与隐私政策" checked={accepted} onChange={(event) => setAccepted(event.target.checked)} /> : null}
            {mode === "login" && step === "credentials" ? <Button className="auth-inline-action" variant="quiet" size="small" type="button" onClick={() => setRecoveryNotice(true)}>忘记密码？</Button> : null}
            {step === "verification" ? <Button className="auth-inline-action" variant="quiet" size="small" type="button" onClick={() => { setStep("credentials"); setEmailCode(""); setTotpCode(""); }}>{mode === "register" ? "返回修改账户信息" : "返回登录信息"}</Button> : null}
            {recoveryNotice ? <Notice title="密码找回暂未开放" tone="warning">请联系服务管理员。</Notice> : null}
            {error || localError ? <Notice title="认证未完成" tone="danger">{error || localError}</Notice> : null}
            <Button type="submit" variant="primary" disabled={busy || (mode === "register" && step === "credentials" && (!accepted || !email || !name || password.length < 12)) || (mode === "register" && step === "verification" && (!emailSent || emailCode.length !== 6)) || (mode === "login" && step === "credentials" && (!email || !password)) || (mode === "login" && step === "verification" && (emailCode.length !== 6 || (totpRequired && totpCode.length !== 6)))}>{busy ? "正在验证…" : mode === "register" ? step === "verification" ? "验证邮箱并创建账户" : "发送验证码并继续" : step === "verification" ? "验证并登录" : "继续"}</Button>
          </form>
          {step === "credentials" ? <div className="auth-switch">{mode === "login" ? "还没有账户？" : "已经注册？"}<Button variant="quiet" size="small" type="button" onClick={() => onModeChange(mode === "login" ? "register" : "login")}>{mode === "login" ? "创建账户" : "返回登录"}</Button></div> : null}
          <div className="auth-demo-notice"><CircleHelp />{allowDemoReturn ? "当前是前端演示；登录不会创建真实账户。" : "邮箱验证码、设备记录与动态口令共同保护账户。"}</div>
    </div>
  );
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return <div className="detail-row"><span>{label}</span><strong>{value}</strong></div>;
}
