import { useState, useRef, useEffect, useCallback } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";
import Icon from "../components/Icon.jsx";
import Toggle from "../components/Toggle.jsx";
import Waveform from "../components/Waveform.jsx";
import LiveChatIcon from "../components/LiveChatIcon.jsx";
import BottomSheet from "../components/BottomSheet.jsx";
import { useChat }      from "../hooks/useChat.js";
import { useProvider }  from "../hooks/useProvider.js";
import { useConnectors } from "../hooks/useConnectors.js";
import { useVoice }     from "../hooks/useVoice.js";

/* ── Static constants (module scope) ─────────────────────────────── */
const ATTACH_OPTIONS = [
  { icon: "image",  label: "صورة",        color: C.accent      },
  { icon: "camera", label: "الكاميرا",     color: "#52c4e0"     },
  { icon: "files",  label: "إضافة ملفات", color: "#a07cff"     },
];

const QUICK_ACTIONS = [
  { icon: "skill",    label: "إضافة مهارات",  color: C.accent    },
  { icon: "website",  label: "إنشاء موقع",    color: "#00dfa2"   },
  { icon: "app",      label: "تطوير تطبيق",   color: "#ffb830"   },
  { icon: "wand",     label: "إنشاء صورة",    color: "#e052b3"   },
  { icon: "pencil",   label: "تحرير صورة",    color: "#52c4e0"   },
  { icon: "voice",    label: "وضع المحادثة",  color: "#a07cff"   },
  { icon: "calendar", label: "مهام مجدولة",  color: "#ff4d6d"   },
  { icon: "table",    label: "جدول بيانات",  color: "#00dfa2"   },
];

const DOTS_ITEMS = [
  { icon: "star",  label: "مفضلة"            },
  { icon: "edit",  label: "إعادة تسمية"      },
  { icon: "files", label: "عرض جميع الملفات" },
  { icon: "info",  label: "تفاصيل المهمة"    },
  { icon: "trash", label: "حذف", danger: true },
];

/* ── Streaming cursor ────────────────────────────────────────────── */
function StreamingDot() {
  return (
    <span style={{
      display: "inline-block", width: 8, height: 8, borderRadius: "50%",
      background: C.accent, marginRight: 4, verticalAlign: "middle",
      animation: "pulse 1s ease-in-out infinite",
    }} />
  );
}

/* ── Message bubble ──────────────────────────────────────────────── */
function MessageBubble({ msg }) {
  const isUser = msg.role === "user";
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: isUser ? "flex-end" : "flex-start" }}>
      <div style={{
        maxWidth: "85%", padding: "10px 14px",
        borderRadius: isUser ? "18px 18px 4px 18px" : "18px 18px 18px 4px",
        background: isUser ? (msg.isError ? `${C.danger}22` : C.accent) : C.surface,
        border: isUser ? "none" : `1px solid ${msg.isError ? C.danger : C.border}`,
        fontSize: T.fontMd, color: C.text, lineHeight: 1.55, whiteSpace: "pre-wrap",
        boxShadow: isUser ? `0 2px 12px ${C.accent}30` : "none",
      }}>
        {msg.content || (msg.streaming ? "" : "…")}
        {msg.streaming && <StreamingDot />}
      </div>
      {msg.voiceInput && (
        <div style={{
          display: "flex", alignItems: "center", gap: 4, marginTop: 4,
          fontSize: "10px", color: C.textC,
        }}>
          <Icon name="mic" size={10} color={C.textC} />
          <span>صوتي</span>
        </div>
      )}
    </div>
  );
}

/* ── API Key Sheet ────────────────────────────────────────────────── */
function ApiKeySheet({ modelName, keyType, onClose, onSave }) {
  const [val, setVal] = useState("");
  const label       = keyType === "anthropic" ? "Anthropic API Key" : "OpenAI API Key";
  const placeholder = keyType === "anthropic" ? "sk-ant-..." : "sk-...";

  return (
    <BottomSheet title={`مفتاح ${modelName}`} onClose={onClose}>
      <div style={{ paddingBottom: 8 }}>
        <div style={{ fontSize: T.fontSm, color: C.textB, textAlign: "right", marginBottom: 12 }}>
          أدخل {label} لتفعيل هذا النموذج.
        </div>
        <div style={{
          display: "flex", alignItems: "center", gap: 8,
          background: C.surfaceB, borderRadius: 12, padding: "11px 14px",
          border: `1px solid ${C.border}`, marginBottom: 14,
        }}>
          <input
            autoFocus
            type="password"
            value={val}
            onChange={e => setVal(e.target.value)}
            onKeyDown={e => { if (e.key === "Enter" && val.trim()) { onSave(val.trim()); onClose(); } }}
            placeholder={placeholder}
            style={{
              flex: 1, background: "transparent", border: "none", outline: "none",
              color: C.text, fontSize: T.fontMd, fontFamily: "monospace",
              direction: "ltr", textAlign: "left",
            }}
          />
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <div
            onClick={() => { if (val.trim()) { onSave(val.trim()); onClose(); } }}
            style={{
              flex: 1,
              background: val.trim()
                ? `linear-gradient(135deg, ${C.accent}, ${C.accentB})`
                : C.surfaceC,
              borderRadius: 12, padding: "12px 0", textAlign: "center",
              cursor: val.trim() ? "pointer" : "default",
              transition: "background .2s",
            }}
          >
            <span style={{ fontSize: T.fontMd, color: "white", fontWeight: 600 }}>حفظ</span>
          </div>
          <div
            onClick={onClose}
            style={{
              flex: 1, background: C.surfaceB, borderRadius: 12, padding: "12px 0",
              textAlign: "center", border: `1px solid ${C.border}`, cursor: "pointer",
            }}
          >
            <span style={{ fontSize: T.fontMd, color: C.textB }}>إلغاء</span>
          </div>
        </div>
      </div>
    </BottomSheet>
  );
}

/* ── Voice toast (transient status banner) ───────────────────────── */
function VoiceToast({ text, isError }) {
  return (
    <div style={{
      position: "absolute", bottom: 110, left: 16, right: 16, zIndex: 50,
      background: isError ? `${C.danger}22` : `${C.accent}18`,
      border: `1px solid ${isError ? C.danger : C.accent}44`,
      borderRadius: 12, padding: "9px 14px",
      display: "flex", alignItems: "center", gap: 8,
      animation: "fadeIn .15s ease",
    }}>
      <Icon name="mic" size={14} color={isError ? C.danger : C.accent} />
      <span style={{
        flex: 1, fontSize: T.fontSm,
        color: isError ? C.danger : C.textB,
        textAlign: "right",
      }}>
        {text}
      </span>
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════
   MAIN ChatScreen
══════════════════════════════════════════════════════════════════ */
const ChatScreen = ({ onMenu, onHistory, convId: convIdProp, hasMessages = false }) => {

  /* ── Provider / data hooks ───────────────────────────────────── */
  const {
    activeModel, models, meta, hasRequiredKey,
    switchModel, saveOpenAIKey, saveAnthropicKey,
  } = useProvider();

  const convId = convIdProp ?? (hasMessages ? "main" : "new");
  const { messages, sendMessage, cancelMessage, isStreaming, error } = useChat(convId);
  const { connected } = useConnectors();

  /* ── UI state ────────────────────────────────────────────────── */
  const [input, setInput]                     = useState("");
  const [inputExpanded, setInputExpanded]     = useState(false);
  const [showAttach, setShowAttach]           = useState(false);
  const [showConnPanel, setShowConnPanel]     = useState(false);
  const [showDotsMenu, setShowDotsMenu]       = useState(false);
  const [showModelPicker, setShowModelPicker] = useState(false);
  const [showApiKey, setShowApiKey]           = useState(false);
  const [totalTokens, setTotalTokens]         = useState(122);

  /* Voice state */
  const [voiceInterim, setVoiceInterim]       = useState("");
  const [voiceError, setVoiceError]           = useState("");
  const voiceErrTimer                         = useRef(null);

  const messagesEndRef = useRef(null);

  /* ── Voice hook ──────────────────────────────────────────────── */
  const voice = useVoice({
    onResult: useCallback((text) => {
      setInput(prev => prev.trim() ? `${prev.trim()} ${text}` : text);
      setVoiceInterim("");
    }, []),
    onInterim: useCallback((text) => {
      setVoiceInterim(text);
    }, []),
    onError: useCallback((msg) => {
      setVoiceInterim("");
      setVoiceError(msg);
      clearTimeout(voiceErrTimer.current);
      voiceErrTimer.current = setTimeout(() => setVoiceError(""), 3500);
    }, []),
  });

  /* Cleanup timer on unmount */
  useEffect(() => () => clearTimeout(voiceErrTimer.current), []);

  /* ── Side effects ────────────────────────────────────────────── */
  useEffect(() => {
    const last = messages[messages.length - 1];
    if (last?.usage) setTotalTokens(prev => prev + (last.usage.completion ?? 0));
  }, [messages]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  /* ── Handlers ────────────────────────────────────────────────── */
  const handleSend = useCallback(() => {
    const text = input.trim();
    if (!text || isStreaming) return;
    if (!hasRequiredKey) { setShowApiKey(true); return; }
    sendMessage(text);
    setInput("");
    setVoiceInterim("");
    setShowAttach(false);
  }, [input, isStreaming, hasRequiredKey, sendMessage]);

  const handleKeyDown = useCallback((e) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); handleSend(); }
  }, [handleSend]);

  const handleModelSelect = useCallback((modelName) => {
    switchModel(modelName);
    setShowModelPicker(false);
  }, [switchModel]);

  const handleApiKeySave = useCallback((key) => {
    if (meta.keyType === "anthropic") saveAnthropicKey(key);
    else saveOpenAIKey(key);
  }, [meta.keyType, saveAnthropicKey, saveOpenAIKey]);

  const handleMicClick = useCallback(() => {
    if (!voice.isSupported) {
      setVoiceError("التعرف على الكلام غير مدعوم في هذا المتصفح.");
      clearTimeout(voiceErrTimer.current);
      voiceErrTimer.current = setTimeout(() => setVoiceError(""), 3500);
      return;
    }
    voice.toggle();
  }, [voice]);

  /* ── Derived ─────────────────────────────────────────────────── */
  const displayMessages = messages.length > 0
    ? messages
    : (hasMessages
        ? [
            {
              id: "demo1", role: "user",
              content: "ابدأ مشروع React جديد مع TypeScript وقم بإعداد المسار.",
            },
            {
              id: "demo2", role: "assistant",
              content: "بالتأكيد! سأقوم بإعداد مشروع React مع TypeScript الآن.\n\n**الخطوات:**\n1. إنشاء هيكل المشروع\n2. تكوين tsconfig.json\n3. إعداد React Router v6\n\nجاري التنفيذ...",
            },
          ]
        : []);

  /* The value actually shown inside the input / textarea */
  const displayInput = voice.isListening && voiceInterim ? voiceInterim : input;
  const inputIsInterim = voice.isListening && voiceInterim && !input;

  const firstConn  = connected[0];
  const extraCount = connected.length > 1 ? `+${connected.length - 1}` : null;

  /* ──────────────────────────────────────────────────────────────
     RENDER
  ────────────────────────────────────────────────────────────── */
  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%", position: "relative" }}>

      {/* ── TOP BAR ─────────────────────────────────────────────── */}
      <div style={{
        display: "flex", alignItems: "center", justifyContent: "space-between",
        padding: "12px 16px 8px", borderBottom: `1px solid ${C.border}`,
        background: C.bg, zIndex: 10, gap: 8,
        flexShrink: 0,
      }}>
        {/* Left: history + share */}
        <div style={{ display: "flex", alignItems: "center", gap: 2 }}>
          <div
            onClick={onHistory}
            title="السجل"
            style={{
              cursor: "pointer", padding: "5px 6px",
              borderRadius: 10,
              transition: "background .15s",
            }}
          >
            <Icon name="history" size={20} color={C.textB} />
          </div>
          {hasMessages && (
            <div style={{ cursor: "pointer", padding: "5px 6px", borderRadius: 10 }}>
              <Icon name="share" size={18} color={C.textB} />
            </div>
          )}
        </div>

        {/* Centre: model selector */}
        <div
          onClick={() => setShowModelPicker(true)}
          style={{
            display: "flex", alignItems: "center", gap: 6, cursor: "pointer",
            background: C.surface, borderRadius: 20, padding: "6px 12px",
            border: `1px solid ${C.border}`,
            transition: "border-color .15s",
          }}
        >
          <Icon name={activeModel.includes("Local") ? "cpu" : "cloud"} size={14} color={C.accent} />
          <span style={{ fontSize: T.fontMd, color: C.text, fontWeight: 600 }}>
            {activeModel}
          </span>
          <Icon name="chevronDown" size={14} color={C.textB} />
        </div>

        {/* Right: token badge + menu */}
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <div style={{
            background: C.surface, borderRadius: 16, padding: "5px 10px",
            border: `1px solid ${C.border}`,
            display: "flex", alignItems: "center", gap: 5,
          }}>
            <Icon name="zap" size={12} color={C.accent} />
            <span style={{ fontSize: "11px", color: C.accent, fontWeight: 700 }}>
              {totalTokens}
            </span>
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

      {/* ── MESSAGES ─────────────────────────────────────────────── */}
      <div style={{
        flex: 1, overflowY: "auto", padding: "16px",
        display: "flex", flexDirection: "column", gap: 12,
      }}>
        {displayMessages.length === 0 && (
          <div style={{
            flex: 1, display: "flex", alignItems: "center", justifyContent: "center",
            flexDirection: "column", gap: 16, paddingTop: 40,
          }}>
            <div style={{
              width: 68, height: 68, borderRadius: "50%",
              background: `radial-gradient(circle, ${C.accent}28 0%, transparent 70%)`,
              display: "flex", alignItems: "center", justifyContent: "center",
              animation: "glow 2.5s ease-in-out infinite",
              border: `1px solid ${C.accent}25`,
            }}>
              <Icon name="bot" size={32} color={C.accent} />
            </div>
            <span style={{ fontSize: T.fontXl, color: C.text, fontWeight: 700 }}>
              كيف يمكنني مساعدتك؟
            </span>
            {error && (
              <span style={{
                fontSize: T.fontSm, color: C.danger, textAlign: "center",
                maxWidth: 260, background: `${C.danger}15`, borderRadius: 10,
                padding: "8px 12px", border: `1px solid ${C.danger}30`,
              }}>
                {error}
              </span>
            )}
          </div>
        )}

        {displayMessages.map(m => (
          <MessageBubble key={m.id ?? m.content?.slice(0, 20)} msg={m} />
        ))}

        {isStreaming && (
          <div
            onClick={cancelMessage}
            style={{
              alignSelf: "center",
              background: C.surfaceB, borderRadius: 20,
              padding: "6px 18px", border: `1px solid ${C.border}`,
              cursor: "pointer", fontSize: T.fontSm, color: C.textB,
              display: "flex", alignItems: "center", gap: 6,
            }}
          >
            <div style={{
              width: 8, height: 8, borderRadius: 2,
              background: C.danger, flexShrink: 0,
            }} />
            إيقاف التوليد
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* ── CONNECTOR PANEL ──────────────────────────────────────── */}
      {showConnPanel && (
        <div style={{
          position: "absolute", bottom: 130, left: 12, right: 12, zIndex: 30,
          background: C.surface, borderRadius: 16,
          border: `1px solid ${C.border}`, padding: "14px",
          animation: "slideUp .18s ease",
          boxShadow: `0 8px 32px rgba(0,0,0,0.5)`,
        }}>
          <div style={{
            fontSize: T.fontSm, color: C.textB, textAlign: "right",
            marginBottom: 10, fontWeight: 600,
          }}>
            الموصّلات النشطة
          </div>
          {connected.length === 0 && (
            <div style={{ fontSize: T.fontSm, color: C.textC, textAlign: "center", padding: "8px 0" }}>
              لا توجد موصلات نشطة
            </div>
          )}
          {connected.map(c => (
            <div key={c.id} style={{
              display: "flex", alignItems: "center", gap: 10,
              padding: "8px 0", borderBottom: `1px solid ${C.border}`,
            }}>
              <div style={{
                width: 28, height: 28, borderRadius: 8,
                background: c.color,
                display: "flex", alignItems: "center", justifyContent: "center",
              }}>
                <span style={{ fontSize: 12, color: "white", fontWeight: 700 }}>
                  {c.name.charAt(0)}
                </span>
              </div>
              <span style={{ flex: 1, fontSize: T.fontSm, color: C.text, textAlign: "right" }}>
                {c.name}
              </span>
              <Toggle
                checked={c.enabled}
                onChange={() => {}}
              />
            </div>
          ))}
          <div
            onClick={() => setShowConnPanel(false)}
            style={{
              marginTop: 10, textAlign: "center",
              fontSize: T.fontSm, color: C.textC, cursor: "pointer",
            }}
          >
            إغلاق
          </div>
        </div>
      )}

      {/* ── ATTACH PANEL ─────────────────────────────────────────── */}
      {showAttach && (
        <div style={{
          position: "absolute",
          bottom: inputExpanded ? 220 : 130,
          left: 0, right: 0,
          background: C.surface, borderTop: `1px solid ${C.border}`,
          padding: "14px 16px", zIndex: 20,
          animation: "slideUp .18s ease",
        }}>
          <div style={{ display: "flex", gap: 16, marginBottom: 14 }}>
            {ATTACH_OPTIONS.map(a => (
              <div
                key={a.label}
                style={{
                  display: "flex", flexDirection: "column",
                  alignItems: "center", gap: 6, cursor: "pointer",
                }}
              >
                <div style={{
                  width: 52, height: 52, borderRadius: 14,
                  background: `${a.color}20`, border: `1px solid ${a.color}45`,
                  display: "flex", alignItems: "center", justifyContent: "center",
                  transition: "transform .12s",
                }}>
                  <Icon name={a.icon} size={22} color={a.color} />
                </div>
                <span style={{ fontSize: "11px", color: C.textB }}>{a.label}</span>
              </div>
            ))}
          </div>

          <div style={{ overflowX: "auto", display: "flex", gap: 10, paddingBottom: 4 }}>
            {QUICK_ACTIONS.map(q => (
              <div
                key={q.label}
                onClick={() => setShowAttach(false)}
                style={{
                  flexShrink: 0,
                  display: "flex", flexDirection: "column",
                  alignItems: "center", gap: 6,
                  cursor: "pointer",
                  background: C.surfaceB, borderRadius: 12,
                  padding: "10px 14px", border: `1px solid ${C.border}`,
                  minWidth: 76,
                }}
              >
                <Icon name={q.icon} size={20} color={q.color} />
                <span style={{
                  fontSize: "11px", color: C.textB,
                  textAlign: "center", whiteSpace: "nowrap",
                }}>
                  {q.label}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── VOICE TOAST ───────────────────────────────────────────── */}
      {(voice.isListening && !voiceInterim) && (
        <VoiceToast text="جارٍ الاستماع… تحدث الآن" />
      )}
      {voiceError && (
        <VoiceToast text={voiceError} isError />
      )}

      {/* ── INPUT BAR ────────────────────────────────────────────── */}
      <div style={{
        padding: "8px 12px 10px", background: C.bg,
        borderTop: `1px solid ${C.border}`, zIndex: 10,
        flexShrink: 0,
      }}>
        <div style={{
          background: C.surface,
          borderRadius: inputExpanded ? 18 : 28,
          border: `1.5px solid ${voice.isListening ? `${C.accent}60` : C.borderB}`,
          padding: inputExpanded ? "12px 14px" : "0",
          transition: "border-color .2s, border-radius .2s",
          boxShadow: voice.isListening
            ? `0 0 0 3px ${C.accent}18`
            : "none",
        }}>

          {/* Expanded textarea */}
          {inputExpanded && (
            <textarea
              value={displayInput}
              onChange={e => { if (!voice.isListening) setInput(e.target.value); }}
              onKeyDown={handleKeyDown}
              placeholder={voice.isListening ? "جارٍ الاستماع…" : "قم بتعيين مهمة أو اسأل أي شيء"}
              rows={4}
              style={{
                width: "100%", background: "transparent",
                border: "none", outline: "none",
                color: inputIsInterim ? C.textB : C.text,
                fontSize: T.fontMd, resize: "none",
                fontFamily: "inherit", direction: "rtl",
                fontStyle: inputIsInterim ? "italic" : "normal",
              }}
            />
          )}

          {/* Bottom row */}
          <div style={{
            display: "flex", alignItems: "center", gap: 8,
            padding: inputExpanded ? "8px 0 0" : "4px 12px",
          }}>
            {/* Expand toggle */}
            <div
              onClick={() => setInputExpanded(v => !v)}
              style={{ cursor: "pointer", flexShrink: 0, padding: "2px" }}
            >
              <svg
                width="18" height="18" viewBox="0 0 18 18" fill="none"
                style={{
                  transform: inputExpanded ? "rotate(0deg)" : "rotate(180deg)",
                  transition: "transform .2s",
                }}
              >
                <path d="M4.5 11.25L9 6.75L13.5 11.25"
                  stroke={C.textB} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </div>

            {/* Inline input (collapsed mode) */}
            {!inputExpanded && (
              <input
                value={displayInput}
                onChange={e => { if (!voice.isListening) setInput(e.target.value); }}
                onKeyDown={handleKeyDown}
                placeholder={voice.isListening ? "جارٍ الاستماع…" : "قم بتعيين مهمة أو اسأل أي شيء"}
                style={{
                  flex: 1, background: "transparent", border: "none", outline: "none",
                  color: inputIsInterim ? C.textB : C.text,
                  fontSize: T.fontMd, textAlign: "right",
                  direction: "rtl", fontFamily: "inherit",
                  fontStyle: inputIsInterim ? "italic" : "normal",
                }}
              />
            )}
            {inputExpanded && <div style={{ flex: 1 }} />}

            {/* Connectors badge */}
            <div
              onClick={() => setShowConnPanel(v => !v)}
              style={{
                cursor: "pointer", display: "flex", alignItems: "center", gap: 4,
                background: showConnPanel ? `${C.accent}18` : C.surfaceB,
                borderRadius: 16, padding: "4px 8px",
                border: `1px solid ${showConnPanel ? C.accent + "44" : C.border}`,
                transition: "all .15s",
              }}
            >
              {firstConn ? (
                <div style={{
                  width: 18, height: 18, borderRadius: 4,
                  background: firstConn.color,
                  display: "flex", alignItems: "center", justifyContent: "center",
                }}>
                  <span style={{ fontSize: "9px", color: "white", fontWeight: 700 }}>
                    {firstConn.name.charAt(0)}
                  </span>
                </div>
              ) : (
                <div style={{
                  width: 18, height: 18, borderRadius: 4,
                  background: "#ea4335",
                  display: "flex", alignItems: "center", justifyContent: "center",
                }}>
                  <span style={{ fontSize: "9px", color: "white", fontWeight: 700 }}>G</span>
                </div>
              )}
              {extraCount && (
                <span style={{ fontSize: "11px", color: C.textB }}>{extraCount}</span>
              )}
            </div>

            {!hasMessages && <LiveChatIcon size={22} active={false} />}

            {/* ── MIC BUTTON ──────────────────────────────────── */}
            <div
              onClick={handleMicClick}
              title={
                !voice.isSupported ? "غير مدعوم في هذا المتصفح"
                : voice.isListening ? "إيقاف الاستماع"
                : "بدء الإدخال الصوتي"
              }
              style={{
                position: "relative",
                cursor: "pointer",
                padding: "2px",
                opacity: 1,
                flexShrink: 0,
              }}
            >
              {voice.isListening ? (
                <Waveform active size={22} color={C.accent} />
              ) : (
                <Icon
                  name="mic"
                  size={20}
                  color={voice.isSupported ? C.textB : C.textC}
                />
              )}

              {/* Recording indicator dot */}
              {voice.isListening && (
                <div style={{
                  position: "absolute", top: -1, right: -1,
                  width: 7, height: 7, borderRadius: "50%",
                  background: C.danger,
                  boxShadow: `0 0 6px ${C.danger}`,
                  animation: "pulse 1s ease-in-out infinite",
                }} />
              )}
            </div>

            {/* Attach button */}
            <div
              onClick={() => setShowAttach(v => !v)}
              style={{ cursor: "pointer", flexShrink: 0, padding: "2px" }}
            >
              <Icon name="plus" size={22} color={showAttach ? C.accent : C.textB} />
            </div>

            {/* Send / Stop button */}
            <div
              onClick={isStreaming ? cancelMessage : handleSend}
              style={{
                width: 34, height: 34, borderRadius: "50%",
                background: (isStreaming || displayInput.trim())
                  ? `linear-gradient(135deg, ${C.accent}, ${C.accentB})`
                  : C.surfaceC,
                display: "flex", alignItems: "center", justifyContent: "center",
                cursor: (isStreaming || displayInput.trim()) ? "pointer" : "default",
                transition: "background .2s",
                flexShrink: 0,
                boxShadow: (isStreaming || displayInput.trim())
                  ? `0 2px 12px ${C.accent}45`
                  : "none",
              }}
            >
              {isStreaming
                ? <Icon name="x"    size={14} color="white" />
                : <Icon name="send" size={16} color={displayInput.trim() ? "white" : C.textC} />
              }
            </div>
          </div>
        </div>
      </div>

      {/* ── MODEL PICKER ─────────────────────────────────────────── */}
      {showModelPicker && (
        <BottomSheet title="اختر النموذج" onClose={() => setShowModelPicker(false)}>
          {models.map(m => (
            <div
              key={m}
              onClick={() => handleModelSelect(m)}
              style={{
                display: "flex", alignItems: "center", justifyContent: "space-between",
                padding: "14px 0", borderBottom: `1px solid ${C.border}`, cursor: "pointer",
              }}
            >
              <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                <div style={{
                  width: 32, height: 32, borderRadius: 10,
                  background: `${C.accent}18`,
                  border: `1px solid ${C.accent}30`,
                  display: "flex", alignItems: "center", justifyContent: "center",
                }}>
                  <Icon name={m.includes("Local") ? "cpu" : "cloud"} size={16} color={C.accent} />
                </div>
                <div>
                  <div style={{ fontSize: T.fontMd, color: C.text }}>{m}</div>
                  <div style={{ fontSize: "10px", color: C.textC }}>
                    {m.includes("Local") ? "على الجهاز" : "سحابي"}
                  </div>
                </div>
              </div>
              {activeModel === m && (
                <div style={{
                  width: 20, height: 20, borderRadius: "50%",
                  background: `${C.accent}25`,
                  display: "flex", alignItems: "center", justifyContent: "center",
                }}>
                  <Icon name="check" size={12} color={C.accent} />
                </div>
              )}
            </div>
          ))}
        </BottomSheet>
      )}

      {/* ── API KEY SHEET ─────────────────────────────────────────── */}
      {showApiKey && (
        <ApiKeySheet
          modelName={activeModel}
          keyType={meta.keyType}
          onClose={() => setShowApiKey(false)}
          onSave={handleApiKeySave}
        />
      )}

      {/* ── DOTS CONTEXT MENU ─────────────────────────────────────── */}
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

    </div>
  );
};

export default ChatScreen;
