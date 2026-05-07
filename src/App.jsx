import { useState, useEffect } from "react";
import C from "./theme/colors.js";
import T from "./theme/typography.js";
import Icon from "./components/Icon.jsx";
import { ErrorBoundary, ScreenBoundary } from "./components/ErrorBoundary.jsx";
import { AppProvider } from "./context/AppContext.jsx";
import { useApp } from "./context/useApp.js";
import HistorySidebar from "./components/HistorySidebar.jsx";

import ChatScreen         from "./screens/ChatScreen.jsx";
import ScheduledScreen    from "./screens/ScheduledScreen.jsx";
import KnowledgeScreen    from "./screens/KnowledgeScreen.jsx";
import DataControlsScreen from "./screens/DataControlsScreen.jsx";
import SkillsScreen       from "./screens/SkillsScreen.jsx";
import ConnectorsScreen   from "./screens/ConnectorsScreen.jsx";
import IntegrationsScreen from "./screens/IntegrationsScreen.jsx";
import SettingsScreen     from "./screens/SettingsScreen.jsx";

/* ── Bottom nav ──────────────────────────────────────────────────── */
const NAV_ITEMS = [
  { id: "chat_new",    icon: "chat",     label: "جديد"    },
  { id: "chat_active", icon: "bot",      label: "دردشة"   },
  { id: "settings",   icon: "settings", label: "إعدادات" },
  { id: "scheduled",  icon: "calendar", label: "مجدول"   },
  { id: "skills",     icon: "skill",    label: "مهارات"  },
];

/* Sub-screens that map to the settings nav item */
const SETTINGS_CHILDREN = new Set([
  "knowledge", "data", "connectors", "integrations",
]);

const ALL_SCREENS = [
  "chat_new", "chat_active", "scheduled", "knowledge",
  "data", "skills", "connectors", "integrations", "settings",
];

/* ── Live clock ─────────────────────────────────────────────────── */
function useClock() {
  const fmt = () => {
    const n = new Date();
    return n.getHours().toString().padStart(2, "0") + ":" +
           n.getMinutes().toString().padStart(2, "0");
  };
  const [time, setTime] = useState(fmt);
  useEffect(() => {
    const id = setInterval(() => setTime(fmt()), 10_000);
    return () => clearInterval(id);
  }, []);
  return time;
}

/* ── Signal bars SVG ─────────────────────────────────────────────── */
function SignalBars() {
  return (
    <svg width="16" height="12" viewBox="0 0 16 12" fill="none">
      <rect x="0"  y="8"  width="3" height="4"  rx="1" fill={C.textB} />
      <rect x="4"  y="5"  width="3" height="7"  rx="1" fill={C.textB} />
      <rect x="8"  y="2"  width="3" height="10" rx="1" fill={C.textB} />
      <rect x="12" y="0"  width="3" height="12" rx="1" fill={C.textC} opacity="0.4" />
    </svg>
  );
}

/* ── Battery SVG ─────────────────────────────────────────────────── */
function Battery() {
  return (
    <svg width="22" height="12" viewBox="0 0 22 12" fill="none">
      <rect x="0.5" y="0.5" width="18" height="11" rx="2.5" stroke={C.textB} strokeWidth="1"/>
      <rect x="2" y="2" width="12" height="8" rx="1.5" fill={C.textB} />
      <path d="M20 4.5v3a1.5 1.5 0 0 0 0-3z" fill={C.textB} />
    </svg>
  );
}

/* ── AppShell ────────────────────────────────────────────────────── */
function AppShell() {
  const [screen, setScreen]           = useState("chat_new");
  const [showHistory, setShowHistory] = useState(false);
  const [activeConvId, setActiveConvId] = useState("new");
  const clock = useClock();

  const { conversations, deleteConversation } = useApp();

  const nav = (s) => setScreen(s);
  const activeNavId = SETTINGS_CHILDREN.has(screen) ? "settings" : screen;

  const handleHistorySelect = (convId) => {
    setActiveConvId(convId);
    setScreen("chat_active");
    setShowHistory(false);
  };

  const handleNewChat = () => {
    setActiveConvId("new_" + Date.now());
    setScreen("chat_new");
    setShowHistory(false);
  };

  const handleDeleteConv = (id) => {
    deleteConversation(id);
  };

  const renderScreen = () => {
    switch (screen) {
      case "chat_new":
        return (
          <ScreenBoundary>
            <ChatScreen
              onMenu={() => nav("settings")}
              onHistory={() => setShowHistory(true)}
              convId={activeConvId.startsWith("new") ? activeConvId : "new"}
              hasMessages={false}
            />
          </ScreenBoundary>
        );
      case "chat_active":
        return (
          <ScreenBoundary>
            <ChatScreen
              onMenu={() => nav("settings")}
              onHistory={() => setShowHistory(true)}
              convId={activeConvId}
              hasMessages={true}
            />
          </ScreenBoundary>
        );
      case "scheduled":
        return (
          <ScreenBoundary>
            <ScheduledScreen onBack={() => nav("settings")} />
          </ScreenBoundary>
        );
      case "knowledge":
        return (
          <ScreenBoundary>
            <KnowledgeScreen onBack={() => nav("settings")} />
          </ScreenBoundary>
        );
      case "data":
        return (
          <ScreenBoundary>
            <DataControlsScreen onBack={() => nav("settings")} />
          </ScreenBoundary>
        );
      case "skills":
        return (
          <ScreenBoundary>
            <SkillsScreen onBack={() => nav("settings")} />
          </ScreenBoundary>
        );
      case "connectors":
        return (
          <ScreenBoundary>
            <ConnectorsScreen onBack={() => nav("settings")} />
          </ScreenBoundary>
        );
      case "integrations":
        return (
          <ScreenBoundary>
            <IntegrationsScreen onBack={() => nav("settings")} />
          </ScreenBoundary>
        );
      case "settings":
        return (
          <ScreenBoundary>
            <SettingsScreen onBack={() => nav("chat_new")} onNav={nav} />
          </ScreenBoundary>
        );
      default:
        return (
          <ScreenBoundary>
            <ChatScreen
              onMenu={() => nav("settings")}
              onHistory={() => setShowHistory(true)}
              convId="new"
              hasMessages={false}
            />
          </ScreenBoundary>
        );
    }
  };

  return (
    <div style={{
      minHeight: "100vh",
      background: "radial-gradient(ellipse at 50% 30%, #150a30 0%, #06060e 55%, #030308 100%)",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      fontFamily: "'Segoe UI', Tahoma, Geneva, Verdana, sans-serif",
      padding: "20px 0",
    }}>

      {/* ── Phone frame ─────────────────────────────────────────── */}
      <div style={{
        width: 375,
        height: 812,
        background: C.bg,
        borderRadius: 46,
        overflow: "hidden",
        display: "flex",
        flexDirection: "column",
        boxShadow: [
          "0 0 0 1px #3d2d7a",
          "0 0 0 3px #1a1230",
          "0 40px 100px rgba(80,40,180,0.38)",
          "0 0 80px rgba(124,95,255,0.08)",
        ].join(", "),
        position: "relative",
      }}>

        {/* Subtle top glow bar */}
        <div style={{
          position: "absolute", top: 0, left: "20%", right: "20%", height: 1,
          background: "linear-gradient(90deg, transparent, #7c5fff55, transparent)",
          zIndex: 1,
        }} />

        {/* Status bar */}
        <div style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "14px 22px 8px",
          fontSize: "12px",
          color: C.textB,
          flexShrink: 0,
          background: C.bg,
          zIndex: 2,
        }}>
          {/* Time */}
          <span style={{
            fontWeight: 700, letterSpacing: "0.03em",
            fontVariantNumeric: "tabular-nums",
            color: C.text, fontSize: "13px",
          }}>
            {clock}
          </span>

          {/* Notch pill */}
          <div style={{
            width: 110, height: 18,
            background: "#000",
            borderRadius: 9,
            border: `1px solid #222238`,
            display: "flex", alignItems: "center", justifyContent: "center", gap: 6,
          }}>
            <div style={{ width: 6, height: 6, borderRadius: "50%", background: "#2d2d50" }} />
            <div style={{ width: 40, height: 8, background: "#191928", borderRadius: 4 }} />
          </div>

          {/* Status icons */}
          <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
            <SignalBars />
            <Battery />
          </div>
        </div>

        {/* Screen area */}
        <div style={{ flex: 1, overflow: "hidden", position: "relative" }}>
          {renderScreen()}

          {/* History sidebar overlay */}
          {showHistory && (
            <HistorySidebar
              conversations={conversations}
              activeConvId={activeConvId}
              onSelect={handleHistorySelect}
              onNew={handleNewChat}
              onDelete={handleDeleteConv}
              onClose={() => setShowHistory(false)}
            />
          )}
        </div>

        {/* Bottom nav */}
        <div style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-around",
          padding: "10px 20px 14px",
          background: C.bg,
          borderTop: `1px solid ${C.border}`,
          flexShrink: 0,
        }}>
          {NAV_ITEMS.map(n => {
            const active = activeNavId === n.id;
            return (
              <div
                key={n.id}
                onClick={() => nav(n.id)}
                style={{
                  display: "flex", flexDirection: "column",
                  alignItems: "center", gap: 4,
                  cursor: "pointer",
                  transition: "opacity .2s",
                }}
              >
                <div style={{
                  width: 36, height: 36, borderRadius: 12,
                  display: "flex", alignItems: "center", justifyContent: "center",
                  background: active ? `${C.accent}20` : "transparent",
                  border: active ? `1px solid ${C.accent}35` : "1px solid transparent",
                  transition: "all .2s",
                  boxShadow: active ? `0 0 12px ${C.accentGlow}` : "none",
                }}>
                  <Icon name={n.icon} size={20} color={active ? C.accent : C.textC} />
                </div>
                <span style={{
                  fontSize: "10px",
                  color: active ? C.accent : C.textC,
                  fontWeight: active ? 700 : 400,
                  transition: "color .2s",
                }}>
                  {n.label}
                </span>
              </div>
            );
          })}
        </div>

        {/* Home indicator */}
        <div style={{
          width: 100, height: 4,
          background: `linear-gradient(90deg, transparent, ${C.borderB}, transparent)`,
          borderRadius: 2,
          margin: "0 auto 8px",
          flexShrink: 0,
        }} />
      </div>

      {/* ── Dev screen selector ──────────────────────────────────── */}
      <div style={{
        position: "fixed", bottom: 16, left: "50%",
        transform: "translateX(-50%)",
        display: "flex", gap: 5, flexWrap: "wrap",
        justifyContent: "center", maxWidth: 560, zIndex: 100,
      }}>
        {ALL_SCREENS.map(s => (
          <div
            key={s}
            onClick={() => nav(s)}
            style={{
              background: screen === s
                ? `linear-gradient(135deg, ${C.accent}, ${C.accentB})`
                : C.surface,
              color: screen === s ? "white" : C.textC,
              padding: "4px 10px",
              borderRadius: 7,
              fontSize: "10px",
              cursor: "pointer",
              border: `1px solid ${screen === s ? C.accent : C.border}`,
              fontWeight: screen === s ? 700 : 400,
              transition: "all .15s",
              letterSpacing: "0.02em",
            }}
          >
            {s}
          </div>
        ))}
      </div>
    </div>
  );
}

export default function App() {
  return (
    <ErrorBoundary>
      <AppProvider>
        <AppShell />
      </AppProvider>
    </ErrorBoundary>
  );
}
