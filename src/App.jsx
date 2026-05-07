import { useState } from "react";
import C from "./theme/colors.js";
import T from "./theme/typography.js";
import Icon from "./components/Icon.jsx";

import ChatScreen        from "./screens/ChatScreen.jsx";
import ScheduledScreen   from "./screens/ScheduledScreen.jsx";
import KnowledgeScreen   from "./screens/KnowledgeScreen.jsx";
import DataControlsScreen from "./screens/DataControlsScreen.jsx";
import SkillsScreen      from "./screens/SkillsScreen.jsx";
import ConnectorsScreen  from "./screens/ConnectorsScreen.jsx";
import IntegrationsScreen from "./screens/IntegrationsScreen.jsx";
import SettingsScreen    from "./screens/SettingsScreen.jsx";

/* ── Bottom nav items ────────────────────────────────────────────── */
const NAV_ITEMS = [
  { id: "chat_new",    icon: "chat",     label: "جديد"    },
  { id: "chat_active", icon: "bot",      label: "دردشة"   },
  { id: "settings",   icon: "settings", label: "إعدادات" },
  { id: "scheduled",  icon: "calendar", label: "مجدول"   },
  { id: "skills",     icon: "skill",    label: "مهارات"  },
];

export default function App() {
  const [screen, setScreen] = useState("chat_new");
  const nav = (s) => setScreen(s);

  /* Screen map — built inline so each screen gets fresh props on mount */
  const renderScreen = () => {
    switch (screen) {
      case "chat_new":
        return <ChatScreen onMenu={() => nav("settings")} hasMessages={false} />;
      case "chat_active":
        return <ChatScreen onMenu={() => nav("settings")} hasMessages={true} />;
      case "scheduled":
        return <ScheduledScreen onBack={() => nav("settings")} />;
      case "knowledge":
        return <KnowledgeScreen onBack={() => nav("settings")} />;
      case "data":
        return <DataControlsScreen onBack={() => nav("settings")} />;
      case "skills":
        return <SkillsScreen onBack={() => nav("settings")} />;
      case "connectors":
        return <ConnectorsScreen onBack={() => nav("settings")} />;
      case "integrations":
        return <IntegrationsScreen onBack={() => nav("settings")} />;
      case "settings":
        return (
          <SettingsScreen
            onBack={() => nav("chat_new")}
            onNav={nav}
          />
        );
      default:
        return <ChatScreen onMenu={() => nav("settings")} hasMessages={false} />;
    }
  };

  const ALL_SCREENS = [
    "chat_new", "chat_active", "scheduled", "knowledge",
    "data", "skills", "connectors", "integrations", "settings",
  ];

  return (
    <div style={{
      minHeight: "100vh",
      background: "#111",
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
        borderRadius: 44,
        overflow: "hidden",
        display: "flex",
        flexDirection: "column",
        boxShadow: "0 40px 120px rgba(0,0,0,0.8), 0 0 0 1px #333",
        position: "relative",
      }}>

        {/* Status bar */}
        <div style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "12px 20px 6px",
          fontSize: "12px",
          color: C.textB,
          flexShrink: 0,
          background: C.bg,
        }}>
          <span>١٢:٠٠</span>
          <div style={{
            width: 120,
            height: 16,
            background: C.bg,
            borderRadius: 8,
            border: `1px solid ${C.border}`,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
          }}>
            <div style={{ width: 60, height: 8, background: C.surfaceC, borderRadius: 4 }} />
          </div>
          <span>sudani</span>
        </div>

        {/* Screen area */}
        <div style={{ flex: 1, overflow: "hidden", position: "relative" }}>
          {renderScreen()}
        </div>

        {/* Bottom nav bar */}
        <div style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-around",
          padding: "10px 20px 16px",
          background: C.bg,
          borderTop: `1px solid ${C.border}`,
          flexShrink: 0,
        }}>
          {NAV_ITEMS.map(n => {
            const active = screen === n.id || screen.startsWith(n.id + "_");
            return (
              <div
                key={n.id}
                onClick={() => nav(n.id)}
                style={{
                  display: "flex",
                  flexDirection: "column",
                  alignItems: "center",
                  gap: 4,
                  cursor: "pointer",
                  opacity: active ? 1 : 0.45,
                  transition: "opacity .2s",
                }}
              >
                <Icon name={n.icon} size={22} color={active ? C.accent : C.textB} />
                <span style={{
                  fontSize: "10px",
                  color: active ? C.accent : C.textB,
                  fontWeight: active ? 600 : 400,
                }}>
                  {n.label}
                </span>
              </div>
            );
          })}
        </div>

        {/* Home indicator */}
        <div style={{
          width: 120,
          height: 5,
          background: C.textC,
          borderRadius: 3,
          margin: "0 auto 8px",
          flexShrink: 0,
        }} />
      </div>

      {/* ── Screen selector (outside phone, for demo navigation) ─── */}
      <div style={{
        position: "fixed",
        bottom: 20,
        left: "50%",
        transform: "translateX(-50%)",
        display: "flex",
        gap: 6,
        flexWrap: "wrap",
        justifyContent: "center",
        maxWidth: 600,
        zIndex: 100,
      }}>
        {ALL_SCREENS.map(s => (
          <div
            key={s}
            onClick={() => nav(s)}
            style={{
              background: screen === s ? C.accent : C.surface,
              color: screen === s ? "white" : C.textB,
              padding: "5px 10px",
              borderRadius: 8,
              fontSize: "11px",
              cursor: "pointer",
              border: `1px solid ${screen === s ? C.accent : C.border}`,
              fontWeight: screen === s ? 600 : 400,
              transition: "all .15s",
            }}
          >
            {s}
          </div>
        ))}
      </div>
    </div>
  );
}
