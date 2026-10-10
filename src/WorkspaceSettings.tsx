import { Check, LayoutDashboard, Palette, RotateCcw, Settings2, SlidersHorizontal, Type, X } from "lucide-react";
import type { ReactNode } from "react";

export type InterfaceTheme = "light" | "soft" | "dark";
export type InterfaceDensity = "compact" | "balanced" | "relaxed";
export type InterfaceLayout = "wide" | "focus" | "split";
export type InterfaceAccent = "green" | "blue" | "amber";

export type InterfaceSettingsValue = {
  fontScale: number;
  density: InterfaceDensity;
  layout: InterfaceLayout;
  theme: InterfaceTheme;
  accent: InterfaceAccent;
};

export const DEFAULT_INTERFACE_SETTINGS: InterfaceSettingsValue = {
  fontScale: 1,
  density: "balanced",
  layout: "wide",
  theme: "light",
  accent: "green",
};

export const INTERFACE_PRESETS: Array<{ id: string; name: string; description: string; tone: string; settings: InterfaceSettingsValue }> = [
  { id: "comfortable", name: "舒适阅读", description: "更大的文字与更宽松的间距", tone: "#347a54", settings: { ...DEFAULT_INTERFACE_SETTINGS, fontScale: 1.12, density: "relaxed", layout: "wide", theme: "light", accent: "green" } },
  { id: "focus", name: "专注工作", description: "收窄内容宽度，减少视觉干扰", tone: "#3f6f9c", settings: { ...DEFAULT_INTERFACE_SETTINGS, fontScale: 1.05, density: "balanced", layout: "focus", theme: "soft", accent: "blue" } },
  { id: "night", name: "夜间实验室", description: "低眩光深色界面，适合晚间使用", tone: "#d59a42", settings: { ...DEFAULT_INTERFACE_SETTINGS, fontScale: 1.08, density: "relaxed", layout: "split", theme: "dark", accent: "amber" } },
];

type WorkspaceSettingsProps = {
  open: boolean;
  value: InterfaceSettingsValue;
  onChange: (value: InterfaceSettingsValue) => void;
  onClose: () => void;
  onReset: () => void;
};

function Choice({ active, children, onClick }: { active: boolean; children: ReactNode; onClick: () => void }) {
  return <button type="button" className={`interface-choice ${active ? "is-active" : ""}`} onClick={onClick}>{children}</button>;
}

export function WorkspaceSettings({ open, value, onChange, onClose, onReset }: WorkspaceSettingsProps) {
  if (!open) return null;
  const update = (patch: Partial<InterfaceSettingsValue>) => onChange({ ...value, ...patch });
  return <>
    <button type="button" className="interface-settings-backdrop" aria-label="关闭界面设置" onClick={onClose} />
    <aside className="interface-settings-panel" role="dialog" aria-modal="true" aria-labelledby="interface-settings-title">
      <header className="interface-settings-header">
        <div><div className="interface-settings-kicker"><Settings2 /> INTERFACE</div><h2 id="interface-settings-title">界面设置</h2><p>调节文字、布局和颜色，偏好会保存在此浏览器。</p></div>
        <button type="button" className="interface-settings-close" onClick={onClose} aria-label="关闭设置"><X /></button>
      </header>
      <div className="interface-settings-body">
        <section className="interface-settings-section"><div className="interface-settings-section-title"><SlidersHorizontal /><h3>快速方案</h3></div><div className="interface-presets">
          {INTERFACE_PRESETS.map((preset) => { const active = Object.entries(preset.settings).every(([key, item]) => value[key as keyof InterfaceSettingsValue] === item); return <button type="button" className={`interface-preset ${active ? "is-active" : ""}`} key={preset.id} onClick={() => onChange(preset.settings)}><span className="interface-preset-mark" style={{ background: preset.tone }}>{active ? <Check /> : <i />}</span><span><strong>{preset.name}</strong><small>{preset.description}</small></span></button>; })}
        </div></section>

        <section className="interface-settings-section"><div className="interface-settings-section-title"><Type /><h3>文字大小</h3><output>{Math.round(value.fontScale * 100)}%</output></div><input className="interface-range" aria-label="系统文字大小" type="range" min="0.9" max="1.25" step="0.01" value={value.fontScale} onChange={(event) => update({ fontScale: Number(event.target.value) })} /><div className="interface-range-labels"><span>较小</span><span>标准</span><span>较大</span></div></section>

        <section className="interface-settings-section"><div className="interface-settings-section-title"><LayoutDashboard /><h3>布局密度</h3></div><div className="interface-choice-grid">{(["compact", "balanced", "relaxed"] as InterfaceDensity[]).map((item) => <Choice key={item} active={value.density === item} onClick={() => update({ density: item })}><strong>{item === "compact" ? "紧凑" : item === "relaxed" ? "宽松" : "平衡"}</strong><small>{item === "compact" ? "显示更多" : item === "relaxed" ? "留出呼吸" : "默认间距"}</small></Choice>)}</div></section>

        <section className="interface-settings-section"><div className="interface-settings-section-title"><LayoutDashboard /><h3>内容布局</h3></div><div className="interface-choice-grid">{(["wide", "focus", "split"] as InterfaceLayout[]).map((item) => <Choice key={item} active={value.layout === item} onClick={() => update({ layout: item })}><strong>{item === "wide" ? "宽画布" : item === "focus" ? "阅读栏" : "分栏"}</strong><small>{item === "wide" ? "充分利用空间" : item === "focus" ? "集中阅读" : "平衡信息"}</small></Choice>)}</div></section>

        <section className="interface-settings-section"><div className="interface-settings-section-title"><Palette /><h3>颜色样式</h3></div><div className="interface-style-grid">{([{ id: "light", label: "纸面", color: "#f3f5f4" }, { id: "soft", label: "雾灰", color: "#e9f0f3" }, { id: "dark", label: "夜间", color: "#202a26" }] as Array<{ id: InterfaceTheme; label: string; color: string }>).map((item) => <button type="button" key={item.id} className={`interface-style ${value.theme === item.id ? "is-active" : ""}`} onClick={() => update({ theme: item.id })}><span style={{ background: item.color }} /><strong>{item.label}</strong></button>)}</div><div className="interface-accent-row">{([{ id: "green", label: "绿色", color: "#347a54" }, { id: "blue", label: "蓝色", color: "#3f6f9c" }, { id: "amber", label: "琥珀", color: "#c17e2d" }] as Array<{ id: InterfaceAccent; label: string; color: string }>).map((item) => <button type="button" key={item.id} className={`interface-accent ${value.accent === item.id ? "is-active" : ""}`} aria-label={`${item.label}强调色`} title={item.label} onClick={() => update({ accent: item.id })}><span style={{ background: item.color }} /></button>)}</div></section>
      </div>
      <footer className="interface-settings-footer"><span>自动保存</span><button type="button" onClick={onReset}><RotateCcw /> 恢复默认</button></footer>
    </aside>
  </>;
}
