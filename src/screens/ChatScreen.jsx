import { useState } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";
import R from "../theme/radius.js";
import Icon from "../components/Icon.jsx";
import Toggle from "../components/Toggle.jsx";
import Waveform from "../components/Waveform.jsx";
import LiveChatIcon from "../components/LiveChatIcon.jsx";
import BottomSheet from "../components/BottomSheet.jsx";

const ATTACH_OPTIONS = [
  { icon: "image",    label: "صورة",          color: "#4e8cff" },
  { icon: "camera",   label: "الكاميرا",       color: "#52b3e0" },
  { icon: "files",    label: "إضافة ملفات",   color: "#a052e0" },
];

const QUICK_ACTIONS = [
  { icon: "skill",    label: "إضافة مهارات",  color: "#4e8cff" },
  { icon: "website",  label: "إنشاء موقع",    color: "#52e09a" },
  { icon: "app",      label: "تطوير تطبيق",   color: "#e0a052" },
  { icon: "wand",     label: "إنشاء صورة",    color: "#e052b3" },
  { icon: "pencil",   label: "تحرير صورة",    color: "#52c4e0" },
  { icon: "voice",    label: "وضع المحادثة",   color: "#b352e0" },
  { icon: "calendar", label: "مهام مجدولة",   color: "#e05252" },
  { icon: "table",    label: "جدول بيانات",   color: "#52e0a0" },
];

const MODELS = ["Airi Cloud", "Airi Local", "GPT-4o", "Claude Sonnet"];

const DOTS_ITEMS = [
  { icon: "star",  label: "مفضلة" },
  { icon: "edit",  label: "إعادة تسمية" },
  { icon: "files", label: "عرض جميع الملفات" },
  { icon: "info",  label: "تفاصيل المهمة" },
  { icon: "trash", label: "حذف", danger: true },
];

const CONNECTED_APPS = [
  { name: "Gmail",  icon: "mail",   color: "#ea4335", bg: "#ea433522" },
  { name: "GitHub", icon: "github", color: "#f0f0f0", bg: "#f0f0f022" },
  { name: "OpenAI", icon: "openai", color: "#10a37f", bg: "#10a37f22" },
];

const INITIAL_MESSAGES = [
  {
    role: "user",
    text: "ابدأ مشروع React جديد مع TypeScript وقم بإعداد المسار.",
  },
  {
    role: "assistant",
    text: "بالتأكيد! سأقوم بإعداد مشروع React مع TypeScript الآن.\n\n**الخطوات:**\n1. إنشاء هيكل المشروع\n2. تكوين tsconfig.json\n3. إعداد React Router v6\n\nجاري التنفيذ...",
  },
];

const ChatScreen = ({ onMenu, hasMessages = false }) => {
  const [input, setInput]                   = useState("");
  const [inputExpanded, setInputExpanded]   = useState(false);
  const [showAttach, setShowAttach]         = useState(false);
  const [showConnPanel, setShowConnPanel]   = useState(false);
  const [showDotsMenu, setShowDotsMenu]     = useState(false);
  const [showModelPicker, setShowModelPicker] = useState(false);
  const [activeModel, setActiveModel]       = useState("Airi Cloud");
  const [isRecording, setIsRecording]       = useState(false);
  const [messages]                          = useState(hasMessages ? INITIAL_MESSAGES : []);

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%", position: "relative" }}>

      {/* ── TOP BAR ─────────────────────────────────────────────── */}
      <div style={{
        display: "flex", alignItems: "center", justifyContent: "space-between",
        padding: "12px 16px 8px", borderBottom: `1px solid ${C.border}`,
        background: C.bg, zIndex: 10, gap: 8,
      }}>
        {hasMessages ? (
          <div onClick={() => {}} style={{ cursor: "pointer", padding: "4px 8px 4px 0" }}>
            <Icon name="share" size={20} color={C.textB} />
          </div>
        ) : (
          <div style={{ width: 28 }} />
        )}

        {/* Model selector */}
        <div
          onClick={() => setShowModelPicker(true)}
          style={{
            display: "flex", alignItems: "center", gap: 6, cursor: "pointer",
            background: C.surface, borderRadius: 20, padding: "6px 12px",
            border: `1px solid ${C.border}`,
          }}
        >
          <Icon name={activeModel.includes("Local") ? "cpu" : "cloud"} size={14} color={C.accent} />
          <span style={{ fontSize: T.fontMd, color: C.text, fontWeight: 600 }}>{activeModel}</span>
          <Icon name="chevronDown" size={14} color={C.textB} />
        </div>

        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          {/* Token badge */}
          <div style={{
            background: C.surface, borderRadius: 16, padding: "5px 10px",
            border: `1px solid ${C.border}`, display: "flex", alignItems: "center", gap: 5,
          }}>
            <Icon name="zap" size={12} color={C.accent} />
            <span style={{ fontSize: "11px", color: C.accent, fontWeight: 700 }}>122</span>
            <span style={{ fontSize: "9px", color: C.textB }}>ترقية</span>
          </div>

          {hasMessages ? (
            <div onClick={() => setShowDotsMenu(true)} style={{ cursor: "pointer", padding: 4 }}>
              <Icon name="dots" size={20} color={C.textB} />
            </div>
          ) : (
            <div onClick={onMenu} style={{ cursor: "pointer", padding: 4 }}>
              <Icon name="arrowRight" size={20} color={C.textB} />
            </div>
          )}
        </div>
      </div>

      {/* ── MESSAGES AREA ───────────────────────────────────────── */}
      <div style={{
        flex: 1, overflowY: "auto", padding: "16px",
        display: "flex", flexDirection: "column", gap: 12,
      }}>
        {messages.length === 0 && (
          <div style={{
            flex: 1, display: "flex", alignItems: "center", justifyContent: "center",
            flexDirection: "column", gap: 16, paddingTop: 40,
          }}>
            <div style={{
              width: 64, height: 64, borderRadius: "50%",
              background: `radial-gradient(circle, ${C.accent}22, transparent)`,
              display: "flex", alignItems: "center", justifyContent: "center",
              animation: "glow 2.5s ease-in-out infinite",
            }}>
              <Icon name="bot" size={32} color={C.accent} />
            </div>
            <span style={{ fontSize: T.fontXl, color: C.text, fontWeight: 600 }}>
              كيف يمكنني مساعدتك؟
            </span>
          </div>
        )}

        {messages.map((m, i) => (
          <div
            key={i}
            style={{
              display: "flex", flexDirection: "column",
              alignItems: m.role === "user" ? "flex-end" : "flex-start",
            }}
          >
            <div style={{
              maxWidth: "85%", padding: "10px 14px",
              borderRadius: m.role === "user"
                ? "18px 18px 4px 18px"
                : "18px 18px 18px 4px",
              background: m.role === "user" ? C.accent : C.surface,
              border: m.role === "user" ? "none" : `1px solid ${C.border}`,
              fontSize: T.fontMd, color: C.text, lineHeight: 1.5,
              whiteSpace: "pre-wrap",
            }}>
              {m.text}
            </div>
          </div>
        ))}
      </div>

      {/* ── ATTACH PANEL ────────────────────────────────────────── */}
      {showAttach && (
        <div style={{
          position: "absolute",
          bottom: inputExpanded ? 220 : 140,
          left: 0, right: 0,
          background: C.surface, borderTop: `1px solid ${C.border}`,
          padding: "14px 16px", zIndex: 20,
          animation: "slideUp .2s ease",
        }}>
          {/* Attach type buttons */}
          <div style={{ display: "flex", gap: 12, marginBottom: 14 }}>
            {ATTACH_OPTIONS.map(a => (
              <div
                key={a.label}
                style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 6, cursor: "pointer" }}
              >
                <div style={{
                  width: 52, height: 52, borderRadius: 14,
                  background: `${a.color}22`, border: `1px solid ${a.color}44`,
                  display: "flex", alignItems: "center", justifyContent: "center",
                }}>
                  <Icon name={a.icon} size={22} color={a.color} />
                </div>
                <span style={{ fontSize: "11px", color: C.textB }}>{a.label}</span>
              </div>
            ))}
          </div>

          {/* Quick action chips (horizontally scrollable) */}
          <div style={{ overflowX: "auto", display: "flex", gap: 10, paddingBottom: 4 }}>
            {QUICK_ACTIONS.map(q => (
              <div
                key={q.label}
                onClick={() => setShowAttach(false)}
                style={{
                  flexShrink: 0, display: "flex", flexDirection: "column",
                  alignItems: "center", gap: 6, cursor: "pointer",
                  background: C.surfaceB, borderRadius: 12,
                  padding: "10px 14px", border: `1px solid ${C.border}`,
                  minWidth: 80,
                }}
              >
                <Icon name={q.icon} size={20} color={q.color} />
                <span style={{ fontSize: "11px", color: C.textB, textAlign: "center", whiteSpace: "nowrap" }}>
                  {q.label}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── INPUT BAR ───────────────────────────────────────────── */}
      <div style={{
        padding: "10px 12px", background: C.bg,
        borderTop: `1px solid ${C.border}`, zIndex: 10,
      }}>
        <div style={{
          background: C.surface,
          borderRadius: inputExpanded ? 18 : 28,
          border: `1px solid ${C.borderB}`,
          padding: inputExpanded ? "12px 14px" : "0",
          transition: "all .2s",
        }}>
          {/* Expanded textarea */}
          {inputExpanded && (
            <textarea
              value={input}
              onChange={e => setInput(e.target.value)}
              placeholder="قم بتعيين مهمة أو اسأل أي شيء"
              rows={4}
              style={{
                width: "100%", background: "transparent", border: "none", outline: "none",
                color: C.text, fontSize: T.fontMd, resize: "none",
                fontFamily: "inherit", direction: "rtl",
              }}
            />
          )}

          <div style={{
            display: "flex", alignItems: "center", gap: 8,
            padding: inputExpanded ? "8px 0 0" : "6px 14px",
          }}>
            {/* Expand / collapse toggle */}
            <div
              onClick={() => setInputExpanded(v => !v)}
              style={{ cursor: "pointer", flexShrink: 0 }}
            >
              <Icon
                name="chevronDown"
                size={18}
                color={C.textB}
                style={{
                  transform: inputExpanded ? "rotate(0deg)" : "rotate(180deg)",
                  transition: "transform .2s",
                }}
              />
            </div>

            {/* Collapsed single-line input */}
            {!inputExpanded && (
              <input
                value={input}
                onChange={e => setInput(e.target.value)}
                placeholder="قم بتعيين مهمة أو اسأل أي شيء"
                style={{
                  flex: 1, background: "transparent", border: "none", outline: "none",
                  color: C.text, fontSize: T.fontMd, textAlign: "right",
                  direction: "rtl", fontFamily: "inherit",
                }}
              />
            )}
            {inputExpanded && <div style={{ flex: 1 }} />}

            {/* Active connectors badge */}
            <div
              onClick={() => setShowConnPanel(v => !v)}
              style={{
                cursor: "pointer", display: "flex", alignItems: "center", gap: 4,
                background: C.surfaceB, borderRadius: 16, padding: "4px 8px",
                border: `1px solid ${C.border}`,
              }}
            >
              <div style={{
                width: 18, height: 18, borderRadius: 4,
                background: "#ea4335",
                display: "flex", alignItems: "center", justifyContent: "center",
              }}>
                <span style={{ fontSize: 9, color: "white", fontWeight: 700 }}>M</span>
              </div>
              <span style={{ fontSize: "11px", color: C.textB }}>+2</span>
            </div>

            {/* Live chat pulse (new chat only) */}
            {!hasMessages && <LiveChatIcon size={22} active={false} />}

            {/* Mic / waveform */}
            <div onClick={() => setIsRecording(v => !v)} style={{ cursor: "pointer" }}>
              {isRecording
                ? <Waveform active size={22} color={C.accent} />
                : <Icon name="mic" size={20} color={C.textB} />
              }
            </div>

            {/* Attach toggle */}
            <div onClick={() => setShowAttach(v => !v)} style={{ cursor: "pointer" }}>
              <Icon name="plus" size={22} color={showAttach ? C.accent : C.textB} />
            </div>

            {/* Send */}
            <div style={{
              width: 34, height: 34, borderRadius: "50%",
              background: input.trim() ? C.accent : C.surfaceC,
              display: "flex", alignItems: "center", justifyContent: "center",
              cursor: input.trim() ? "pointer" : "default",
              transition: "background .2s",
            }}>
              <Icon name="send" size={16} color={input.trim() ? C.text : C.textC} />
            </div>
          </div>
        </div>
      </div>

      {/* ── MODEL PICKER SHEET ──────────────────────────────────── */}
      {showModelPicker && (
        <BottomSheet title="اختر النموذج" onClose={() => setShowModelPicker(false)}>
          {MODELS.map(m => (
            <div
              key={m}
              onClick={() => { setActiveModel(m); setShowModelPicker(false); }}
              style={{
                display: "flex", alignItems: "center", justifyContent: "space-between",
                padding: "14px 0", borderBottom: `1px solid ${C.border}`, cursor: "pointer",
              }}
            >
              <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                <Icon name={m.includes("Local") ? "cpu" : "cloud"} size={18} color={C.accent} />
                <span style={{ fontSize: T.fontMd, color: C.text }}>{m}</span>
              </div>
              {activeModel === m && <Icon name="check" size={16} color={C.accent} />}
            </div>
          ))}
        </BottomSheet>
      )}

      {/* ── DOTS CONTEXT MENU ───────────────────────────────────── */}
      {showDotsMenu && (
        <BottomSheet title="" onClose={() => setShowDotsMenu(false)} compact>
          {DOTS_ITEMS.map(d => (
            <div
              key={d.label}
              onClick={() => setShowDotsMenu(false)}
              style={{
                display: "flex", alignItems: "center", gap: 12,
                padding: "13px 0", borderBottom: `1px solid ${C.border}`, cursor: "pointer",
              }}
            >
              <Icon name={d.icon} size={18} color={d.danger ? C.danger : C.textB} />
              <span style={{ fontSize: T.fontMd, color: d.danger ? C.danger : C.text }}>
                {d.label}
              </span>
            </div>
          ))}
        </BottomSheet>
      )}

      {/* ── CONNECTOR PANEL SHEET ───────────────────────────────── */}
      {showConnPanel && (
        <BottomSheet title="الموصلات" onClose={() => setShowConnPanel(false)}>
          <div style={{ marginBottom: 16 }}>
            <div style={{ fontSize: T.fontSm, color: C.textB, marginBottom: 10 }}>متصلة</div>
            {CONNECTED_APPS.map(c => (
              <div
                key={c.name}
                style={{
                  display: "flex", alignItems: "center", gap: 12,
                  padding: "12px 0", borderBottom: `1px solid ${C.border}`,
                }}
              >
                <div style={{
                  width: 36, height: 36, borderRadius: 9,
                  background: c.bg, display: "flex", alignItems: "center", justifyContent: "center",
                }}>
                  <Icon name={c.icon} size={18} color={c.color} />
                </div>
                <span style={{ flex: 1, fontSize: T.fontMd, color: C.text }}>{c.name}</span>
                <Toggle on={true} onChange={() => {}} />
              </div>
            ))}
          </div>

          {/* Action buttons */}
          <div style={{ display: "flex", gap: 10 }}>
            <div
              onClick={() => setShowConnPanel(false)}
              style={{
                flex: 1, background: C.accent, borderRadius: 12, padding: "12px 0",
                display: "flex", alignItems: "center", justifyContent: "center", gap: 6,
                cursor: "pointer",
              }}
            >
              <Icon name="plus" size={16} color="white" />
              <span style={{ fontSize: T.fontMd, color: "white", fontWeight: 600 }}>إضافة موصل</span>
            </div>
            <div style={{
              flex: 1, background: C.surfaceB, borderRadius: 12, padding: "12px 0",
              display: "flex", alignItems: "center", justifyContent: "center",
              border: `1px solid ${C.border}`, cursor: "pointer",
            }}>
              <span style={{ fontSize: T.fontMd, color: C.textB }}>إدارة الموصلات</span>
            </div>
          </div>
        </BottomSheet>
      )}
    </div>
  );
};

export default ChatScreen;
