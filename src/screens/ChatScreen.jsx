import { useState, useRef, useEffect, useCallback } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";
import Icon from "../components/Icon.jsx";
import Toggle from "../components/Toggle.jsx";
import Waveform from "../components/Waveform.jsx";
import LiveChatIcon from "../components/LiveChatIcon.jsx";
import BottomSheet from "../components/BottomSheet.jsx";
import { useChat }           from "../hooks/useChat.js";
import { useProvider }       from "../hooks/useProvider.js";
import { useConnectors }     from "../hooks/useConnectors.js";
import { useVoice }          from "../hooks/useVoice.js";
import { useTextToSpeech }   from "../hooks/useTextToSpeech.js";
import { useReactions }      from "../hooks/useReactions.js";

/* ── Static module-scope constants ───────────────────────────────── */
const ATTACH_OPTIONS = [
  { icon: "image",  label: "صورة",        color: C.accent  },
  { icon: "camera", label: "الكاميرا",     color: "#52c4e0" },
  { icon: "files",  label: "إضافة ملفات", color: "#a07cff" },
];

const QUICK_ACTIONS = [
  { icon: "skill",    label: "إضافة مهارات", color: C.accent  },
  { icon: "website",  label: "إنشاء موقع",   color: "#00dfa2" },
  { icon: "app",      label: "تطوير تطبيق",  color: "#ffb830" },
  { icon: "wand",     label: "إنشاء صورة",   color: "#e052b3" },
  { icon: "pencil",   label: "تحرير صورة",   color: "#52c4e0" },
  { icon: "voice",    label: "وضع المحادثة", color: "#a07cff" },
  { icon: "calendar", label: "مهام مجدولة", color: "#ff4d6d" },
  { icon: "table",    label: "جدول بيانات", color: "#00dfa2" },
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
      display: "inline-block", width: 7, height: 7, borderRadius: "50%",
      background: C.accent, marginRight: 4, verticalAlign: "middle",
      animation: "pulse 1s ease-in-out infinite",
    }} />
  );
}

/* ── Message reactions bar ───────────────────────────────────────── */
function ReactionBar({ msgId, react, getReaction, onSpeak, content, isSpeaking, ttsEnabled }) {
  const current = getReaction(msgId);
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard?.writeText(content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };

  return (
    <div style={{
      display: "flex", alignItems: "center", gap: 2, marginTop: 6,
      animation: "fadeIn .2s ease",
    }}>
      {/* Thumbs up */}
      <div
        onClick={() => react(msgId, "up")}
        style={{
          padding: "4px 6px", borderRadius: 8, cursor: "pointer",
          background: current === "up" ? `${C.success}22` : "transparent",
          transition: "background .15s",
          display: "flex", alignItems: "center",
        }}
      >
        <Icon name="thumbUp" size={13} color={current === "up" ? C.success : C.textC} />
      </div>

      {/* Thumbs down */}
      <div
        onClick={() => react(msgId, "down")}
        style={{
          padding: "4px 6px", borderRadius: 8, cursor: "pointer",
          background: current === "down" ? `${C.danger}22` : "transparent",
          transition: "background .15s",
          display: "flex", alignItems: "center",
        }}
      >
        <Icon name="thumbDown" size={13} color={current === "down" ? C.danger : C.textC} />
      </div>

      {/* Copy */}
      <div
        onClick={handleCopy}
        style={{
          padding: "4px 6px", borderRadius: 8, cursor: "pointer",
          background: copied ? `${C.accent}22` : "transparent",
          display: "flex", alignItems: "center", gap: 3, transition: "background .15s",
        }}
      >
        <Icon name="copy" size={13} color={copied ? C.accent : C.textC} />
        {copied && (
          <span style={{ fontSize: "9px", color: C.accent }}>تم</span>
        )}
      </div>

      {/* Speak */}
      {ttsEnabled !== undefined && (
        <div
          onClick={onSpeak}
          style={{
            padding: "4px 6px", borderRadius: 8, cursor: "pointer",
            background: isSpeaking ? `${C.accent}22` : "transparent",
            display: "flex", alignItems: "center", transition: "background .15s",
          }}
        >
          <Icon
            name={isSpeaking ? "volumeOff" : "volume"}
            size={13}
            color={isSpeaking ? C.accent : C.textC}
          />
        </div>
      )}
    </div>
  );
}

/* ── Message bubble ──────────────────────────────────────────────── */
function MessageBubble({ msg, react, getReaction, onSpeak, isSpeakingThis, ttsEnabled }) {
  const isUser = msg.role === "user";
  const isAssistant = msg.role === "assistant";
  const isDone = isAssistant && !msg.streaming;

  return (
    <div style={{
      display: "flex", flexDirection: "column",
      alignItems: isUser ? "flex-end" : "flex-start",
    }}>
      <div style={{
        maxWidth: "86%", padding: "10px 14px",
        borderRadius: isUser ? "18px 18px 4px 18px" : "18px 18px 18px 4px",
        background: isUser
          ? (msg.isError ? `${C.danger}22` : C.accent)
          : C.surface,
        border: isUser
          ? "none"
          : `1px solid ${msg.isError ? C.danger : C.border}`,
        fontSize: T.fontMd, color: C.text, lineHeight: 1.6, whiteSpace: "pre-wrap",
        boxShadow: isUser ? `0 2px 14px ${C.accent}35` : "none",
      }}>
        {msg.content || (msg.streaming ? "" : "…")}
        {msg.streaming && <StreamingDot />}
      </div>

      {/* Voice label for voice-input messages */}
      {isUser && msg.voiceInput && (
        <div style={{
          display: "flex", alignItems: "center", gap: 3, marginTop: 3,
          fontSize: "9px", color: C.textC,
        }}>
          <Icon name="mic" size={9} color={C.textC} />
          <span>صوتي</span>
        </div>
      )}

      {/* Reactions + tools for completed assistant messages */}
      {isAssistant && isDone && !msg.isError && !msg.cancelled && (
        <ReactionBar
          msgId={msg.id}
          react={react}
          getReaction={getReaction}
          content={msg.content}
          onSpeak={onSpeak}
          isSpeaking={isSpeakingThis}
          ttsEnabled={ttsEnabled}
        />
      )}
    </div>
  );
}

/* ── API Key Sheet ────────────────────────────────────────────────── */
function ApiKeySheet({ modelName, keyType, onClose, onSave }) {
  const [val, setVal] = useState("");
  const label       = keyType === "anthropic" ? "Anthropic API Key" : "OpenAI API Key";
  const placeholder = keyType === "anthropic" ? "sk-ant-..." : "sk-...";

  const save = () => { if (val.trim()) { onSave(val.trim()); onClose(); } };

  return (
    <BottomSheet title={`مفتاح ${modelName}`} onClose={onClose}>
      <div style={{ paddingBottom: 8 }}>
        <div style={{ fontSize: T.fontSm, color: C.textB, textAlign: "right", marginBottom: 12 }}>
          أدخل {label} لتفعيل هذا النموذج.
        </div>
        <div style={{
          display: "flex", alignItems: "center",
          background: C.surfaceB, borderRadius: 12, padding: "11px 14px",
          border: `1px solid ${C.border}`, marginBottom: 14,
        }}>
          <input
            autoFocus
            type="password"
            value={val}
            onChange={e => setVal(e.target.value)}
            onKeyDown={e => e.key === "Enter" && save()}
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
            onClick={save}
            style={{
              flex: 1, borderRadius: 12, padding: "12px 0", textAlign: "center",
              cursor: val.trim() ? "pointer" : "default",
              background: val.trim()
                ? `linear-gradient(135deg, ${C.accent}, ${C.accentB})`
                : C.surfaceC,
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

/* ── Voice status toast ───────────────────────────────────────────── */
function VoiceToast({ text, isError }) {
  return (
    <div style={{
      position: "absolute", bottom: 116, left: 14, right: 14, zIndex: 50,
      background: isError ? `${C.danger}20` : `${C.accent}18`,
      border: `1px solid ${isError ? C.danger : C.accent}44`,
      borderRadius: 12, padding: "9px 14px",
      display: "flex", alignItems: "center", gap: 8,
      animation: "fadeIn .15s ease",
      pointerEvents: "none",
    }}>
      <Icon name="mic" size={14} color={isError ? C.danger : C.accent} />
      <span style={{ flex: 1, fontSize: T.fontSm, color: isError ? C.danger : C.textB, textAlign: "right" }}>
        {text}
      </span>
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════
   MAIN ChatScreen
══════════════════════════════════════════════════════════════════ */
const ChatScreen = ({ onMenu, onHistory, convId: convIdProp, hasMessages = false }) => {

  /* ── Data hooks ──────────────────────────────────────────────── */
  const {
    activeModel, models, meta, hasRequiredKey,
    switchModel, saveOpenAIKey, saveAnthropicKey,
  } = useProvider();

  const convId = convIdProp ?? (hasMessages ? "main" : "new");
  const { messages, sendMessage, cancelMessage, isStreaming, error } = useChat(convId);
  const { connected, toggle: toggleConnector }                       = useConnectors();
  const { reactions, react, getReaction }                            = useReactions(convId);
  const tts                                                          = useTextToSpeech();

  /* ── UI state ────────────────────────────────────────────────── */
  const [input, setInput]                     = useState("");
  const [inputExpanded, setInputExpanded]     = useState(false);
  const [showAttach, setShowAttach]           = useState(false);
  const [showConnPanel, setShowConnPanel]     = useState(false);
  const [showDotsMenu, setShowDotsMenu]       = useState(false);
  const [showModelPicker, setShowModelPicker] = useState(false);
  const [showApiKey, setShowApiKey]           = useState(false);
  const [totalTokens, setTotalTokens]         = useState(122);

  /* Voice input state */
  const [voiceInterim, setVoiceInterim]       = useState("");
  const [voiceError, setVoiceError]           = useState("");
  const voiceErrTimer                         = useRef(null);

  /* Track which message is currently being spoken */
  const [speakingMsgId, setSpeakingMsgId]     = useState(null);

  const messagesEndRef = useRef(null);

  /* ── Voice input hook ────────────────────────────────────────── */
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

  /* Cleanup */
  useEffect(() => () => {
    clearTimeout(voiceErrTimer.current);
  }, []);

  /* ── Token counting ──────────────────────────────────────────── */
  useEffect(() => {
    const last = messages[messages.length - 1];
    if (last?.usage) setTotalTokens(prev => prev + (last.usage.completion ?? 0));
  }, [messages]);

  /* ── Auto-scroll ─────────────────────────────────────────────── */
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  /* ── TTS: speak completed AI responses automatically ─────────── */
  const prevStreamingRef = useRef(false);
  useEffect(() => {
    if (prevStreamingRef.current && !isStreaming && tts.isEnabled) {
      const last = messages[messages.length - 1];
      if (last?.role === "assistant" && !last.streaming && !last.isError && !last.cancelled) {
        setSpeakingMsgId(last.id);
        tts.speak(last.content);
      }
    }
    prevStreamingRef.current = isStreaming;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isStreaming]);

  /* Clear speakingMsgId when TTS finishes */
  useEffect(() => {
    if (!tts.isSpeaking) setSpeakingMsgId(null);
  }, [tts.isSpeaking]);

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

  const handleSendVoice = useCallback((text) => {
    const t = text?.trim();
    if (!t || isStreaming) return;
    if (!hasRequiredKey) { setShowApiKey(true); return; }
    sendMessage(t, { voiceInput: true });
    setInput("");
    setVoiceInterim("");
  }, [isStreaming, hasRequiredKey, sendMessage]);

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

  const handleSpeakMessage = useCallback((msg) => {
    if (speakingMsgId === msg.id && tts.isSpeaking) {
      tts.stop();
      setSpeakingMsgId(null);
    } else {
      setSpeakingMsgId(msg.id);
      tts.speak(msg.content);
    }
  }, [speakingMsgId, tts]);

  /* ── Derived ─────────────────────────────────────────────────── */
  const displayInput    = voice.isListening && voiceInterim ? voiceInterim : input;
  const inputIsInterim  = voice.isListening && !!voiceInterim && !input;

  const displayMessages = messages.length > 0
    ? messages
    : (hasMessages
        ? [
            { id: "demo1", role: "user",
              content: "ابدأ مشروع React جديد مع TypeScript وقم بإعداد المسار." },
            { id: "demo2", role: "assistant",
              content: "بالتأكيد! سأقوم بإعداد مشروع React مع TypeScript الآن.\n\n**الخطوات:**\n1. إنشاء هيكل المشروع\n2. تكوين tsconfig.json\n3. إعداد React Router v6\n\nجاري التنفيذ..." },
          ]
        : []);

  const firstConn  = connected[0];
  const extraCount = connected.length > 1 ? `+${connected.length - 1}` : null;

  /* ══════════════════════════════════════════════════════════════
     RENDER
  ══════════════════════════════════════════════════════════════ */
  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%", position: "relative" }}>

      {/* ── TOP BAR ─────────────────────────────────────────────── */}
      <div style={{
        display: "flex", alignItems: "center", justifyContent: "space-between",
        padding: "12px 14px 8px", borderBottom: `1px solid ${C.border}`,
        background: C.bg, zIndex: 10, gap: 6, flexShrink: 0,
      }}>
        {/* Left: history + share */}
        <div style={{ display: "flex", alignItems: "center", gap: 1 }}>
          <div
            onClick={onHistory}
            title="السجل"
            style={{ cursor: "pointer", padding: "5px 6px", borderRadius: 9 }}
          >
            <Icon name="history" size={19} color={C.textB} />
          </div>
          {/* TTS toggle — inline, no settings page needed */}
          <div
            onClick={tts.toggle}
            title={tts.isEnabled ? "إيقاف الصوت" : "تفعيل الصوت"}
            style={{
              cursor: "pointer", padding: "5px 6px", borderRadius: 9,
              background: tts.isEnabled ? `${C.accent}18` : "transparent",
              transition: "background .15s",
            }}
          >
            <Icon
              name={tts.isEnabled ? "volume" : "volumeOff"}
              size={17}
              color={tts.isEnabled ? C.accent : C.textC}
            />
          </div>
        </div>

        {/* Centre: model pill */}
        <div
          onClick={() => setShowModelPicker(true)}
          style={{
            display: "flex", alignItems: "center", gap: 6, cursor: "pointer",
            background: C.surface, borderRadius: 20, padding: "6px 12px",
            border: `1px solid ${C.border}`, transition: "border-color .15s", flex: 0,
          }}
        >
          <Icon name={activeModel.includes("Local") ? "cpu" : "cloud"} size={13} color={C.accent} />
          <span style={{ fontSize: "13px", color: C.text, fontWeight: 600, whiteSpace: "nowrap" }}>
            {activeModel}
          </span>
          <Icon name="chevronDown" size={13} color={C.textB} />
        </div>

        {/* Right: token + menu */}
        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
          <div style={{
            background: C.surface, borderRadius: 16, padding: "4px 9px",
            border: `1px solid ${C.border}`,
            display: "flex", alignItems: "center", gap: 4,
          }}>
            <Icon name="zap" size={11} color={C.accent} />
            <span style={{ fontSize: "11px", color: C.accent, fontWeight: 700 }}>
              {totalTokens}
            </span>
          </div>
          {hasMessages ? (
            <div onClick={() => setShowDotsMenu(true)} style={{ cursor: "pointer", padding: 4 }}>
              <Icon name="dots" size={19} color={C.textB} />
            </div>
          ) : (
            <div onClick={onMenu} style={{ cursor: "pointer", padding: 4 }}>
              <Icon name="arrowRight" size={19} color={C.textB} />
            </div>
          )}
        </div>
      </div>

      {/* ── MESSAGES ─────────────────────────────────────────────── */}
      <div style={{
        flex: 1, overflowY: "auto", padding: "14px 14px 8px",
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
              border: `1px solid ${C.accent}22`,
            }}>
              <Icon name="bot" size={32} color={C.accent} />
            </div>
            <span style={{ fontSize: T.fontXl, color: C.text, fontWeight: 700 }}>
              كيف يمكنني مساعدتك؟
            </span>
            {error && (
              <div style={{
                fontSize: T.fontSm, color: C.danger, textAlign: "center",
                maxWidth: 260, background: `${C.danger}12`, borderRadius: 10,
                padding: "9px 14px", border: `1px solid ${C.danger}28`,
              }}>
                {error}
              </div>
            )}
          </div>
        )}

        {displayMessages.map(m => (
          <MessageBubble
            key={m.id ?? m.content?.slice(0, 20)}
            msg={m}
            react={react}
            getReaction={getReaction}
            onSpeak={() => handleSpeakMessage(m)}
            isSpeakingThis={speakingMsgId === m.id && tts.isSpeaking}
            ttsEnabled={tts.isSupported ? tts.isEnabled : undefined}
          />
        ))}

        {/* Cancel streaming button */}
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
              width: 7, height: 7, borderRadius: 2,
              background: C.danger, flexShrink: 0,
            }} />
            إيقاف التوليد
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* ── CONNECTOR PANEL (fixed, above input) ──────────────────── */}
      {showConnPanel && (
        <div style={{
          position: "absolute", bottom: 118, left: 10, right: 10, zIndex: 40,
          background: C.surface, borderRadius: 16,
          border: `1px solid ${C.border}`, padding: "12px 14px",
          animation: "slideUp .18s ease",
          boxShadow: `0 8px 36px rgba(0,0,0,0.55)`,
        }}>
          <div style={{
            fontSize: T.fontSm, color: C.textB, textAlign: "right",
            marginBottom: 10, fontWeight: 600,
          }}>
            الموصّلات النشطة
          </div>
          {connected.length === 0 && (
            <div style={{ fontSize: T.fontSm, color: C.textC, textAlign: "center", padding: "6px 0" }}>
              لا توجد موصلات نشطة
            </div>
          )}
          {connected.map((c, i, arr) => (
            <div key={c.id} style={{
              display: "flex", alignItems: "center", gap: 10,
              padding: "9px 0",
              borderBottom: i < arr.length - 1 ? `1px solid ${C.border}` : "none",
            }}>
              <div style={{
                width: 28, height: 28, borderRadius: 8, background: c.bg,
                display: "flex", alignItems: "center", justifyContent: "center",
              }}>
                <Icon name={c.icon} size={14} color={c.color} />
              </div>
              <span style={{ flex: 1, fontSize: T.fontSm, color: C.text, textAlign: "right" }}>
                {c.name}
              </span>
              {/* Fixed: use `on` prop + real toggle */}
              <Toggle on={c.enabled} onChange={() => toggleConnector(c.id)} />
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
          bottom: inputExpanded ? 220 : 118,
          left: 0, right: 0,
          background: C.surface, borderTop: `1px solid ${C.border}`,
          padding: "14px 14px", zIndex: 30,
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
                  background: `${a.color}20`, border: `1px solid ${a.color}44`,
                  display: "flex", alignItems: "center", justifyContent: "center",
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
                  flexShrink: 0, display: "flex", flexDirection: "column",
                  alignItems: "center", gap: 6, cursor: "pointer",
                  background: C.surfaceB, borderRadius: 12,
                  padding: "10px 14px", border: `1px solid ${C.border}`,
                  minWidth: 74,
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

      {/* ── VOICE TOASTS ─────────────────────────────────────────── */}
      {voice.isListening && !voiceInterim && (
        <VoiceToast text="جارٍ الاستماع… تحدث الآن" />
      )}
      {voiceError && (
        <VoiceToast text={voiceError} isError />
      )}

      {/* ── INPUT BAR ────────────────────────────────────────────── */}
      <div style={{
        padding: "8px 10px 10px", background: C.bg,
        borderTop: `1px solid ${C.border}`, zIndex: 20, flexShrink: 0,
      }}>
        <div style={{
          background: C.surface,
          borderRadius: inputExpanded ? 18 : 28,
          border: `1.5px solid ${voice.isListening ? `${C.accent}65` : C.borderB}`,
          padding: inputExpanded ? "12px 14px" : "0",
          transition: "border-color .2s, border-radius .2s, box-shadow .2s",
          boxShadow: voice.isListening ? `0 0 0 3px ${C.accent}15` : "none",
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
                width: "100%", background: "transparent", border: "none", outline: "none",
                color: inputIsInterim ? C.textB : C.text,
                fontSize: T.fontMd, resize: "none", fontFamily: "inherit",
                direction: "rtl",
                fontStyle: inputIsInterim ? "italic" : "normal",
              }}
            />
          )}

          {/* Controls row */}
          <div style={{
            display: "flex", alignItems: "center", gap: 6,
            padding: inputExpanded ? "8px 0 0" : "4px 10px",
          }}>
            {/* Expand toggle */}
            <div
              onClick={() => setInputExpanded(v => !v)}
              style={{ cursor: "pointer", flexShrink: 0, padding: "2px" }}
            >
              <svg
                width="17" height="17" viewBox="0 0 18 18" fill="none"
                style={{
                  transform: inputExpanded ? "rotate(0deg)" : "rotate(180deg)",
                  transition: "transform .2s",
                }}
              >
                <path d="M4.5 11.25L9 6.75L13.5 11.25"
                  stroke={C.textB} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </div>

            {/* Inline input (collapsed) */}
            {!inputExpanded && (
              <input
                value={displayInput}
                onChange={e => { if (!voice.isListening) setInput(e.target.value); }}
                onKeyDown={handleKeyDown}
                placeholder={voice.isListening ? "جارٍ الاستماع…" : "قم بتعيين مهمة أو اسأل أي شيء"}
                style={{
                  flex: 1, background: "transparent", border: "none", outline: "none",
                  color: inputIsInterim ? C.textB : C.text,
                  fontSize: T.fontMd, textAlign: "right", direction: "rtl",
                  fontFamily: "inherit",
                  fontStyle: inputIsInterim ? "italic" : "normal",
                }}
              />
            )}
            {inputExpanded && <div style={{ flex: 1 }} />}

            {/* Connector badge */}
            <div
              onClick={() => setShowConnPanel(v => !v)}
              style={{
                cursor: "pointer", display: "flex", alignItems: "center", gap: 4,
                background: showConnPanel ? `${C.accent}18` : C.surfaceB,
                borderRadius: 14, padding: "4px 8px",
                border: `1px solid ${showConnPanel ? C.accent + "44" : C.border}`,
                transition: "all .15s", flexShrink: 0,
              }}
            >
              {firstConn ? (
                <div style={{
                  width: 17, height: 17, borderRadius: 4, background: firstConn.bg,
                  display: "flex", alignItems: "center", justifyContent: "center",
                }}>
                  <Icon name={firstConn.icon} size={10} color={firstConn.color} />
                </div>
              ) : (
                <div style={{
                  width: 17, height: 17, borderRadius: 4, background: "#ea433522",
                  display: "flex", alignItems: "center", justifyContent: "center",
                }}>
                  <Icon name="mail" size={10} color="#ea4335" />
                </div>
              )}
              {extraCount && (
                <span style={{ fontSize: "10px", color: C.textB }}>{extraCount}</span>
              )}
            </div>

            {!hasMessages && <LiveChatIcon size={20} active={false} />}

            {/* ── Mic button ──────────────────────────────────── */}
            <div
              onClick={handleMicClick}
              title={
                !voice.isSupported ? "غير مدعوم"
                : voice.isListening ? "إيقاف الاستماع"
                : "إدخال صوتي"
              }
              style={{ position: "relative", cursor: "pointer", flexShrink: 0, padding: "2px" }}
            >
              {voice.isListening
                ? <Waveform active size={20} color={C.accent} />
                : <Icon name="mic" size={19} color={voice.isSupported ? C.textB : C.textC} />
              }
              {/* Red recording dot */}
              {voice.isListening && (
                <div style={{
                  position: "absolute", top: 0, right: 0,
                  width: 6, height: 6, borderRadius: "50%",
                  background: C.danger, boxShadow: `0 0 5px ${C.danger}`,
                  animation: "pulse 1s ease-in-out infinite",
                }} />
              )}
            </div>

            {/* Attach */}
            <div
              onClick={() => setShowAttach(v => !v)}
              style={{ cursor: "pointer", flexShrink: 0, padding: "2px" }}
            >
              <Icon name="plus" size={21} color={showAttach ? C.accent : C.textB} />
            </div>

            {/* Send / Stop */}
            <div
              onClick={isStreaming ? cancelMessage : handleSend}
              style={{
                width: 33, height: 33, borderRadius: "50%",
                background: (isStreaming || displayInput.trim())
                  ? `linear-gradient(135deg, ${C.accent}, ${C.accentB})`
                  : C.surfaceC,
                display: "flex", alignItems: "center", justifyContent: "center",
                cursor: (isStreaming || displayInput.trim()) ? "pointer" : "default",
                transition: "background .2s", flexShrink: 0,
                boxShadow: (isStreaming || displayInput.trim())
                  ? `0 2px 10px ${C.accent}40` : "none",
              }}
            >
              {isStreaming
                ? <Icon name="x"    size={13} color="white" />
                : <Icon name="send" size={15} color={displayInput.trim() ? "white" : C.textC} />
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
                padding: "13px 0", borderBottom: `1px solid ${C.border}`, cursor: "pointer",
              }}
            >
              <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                <div style={{
                  width: 32, height: 32, borderRadius: 10,
                  background: `${C.accent}18`, border: `1px solid ${C.accent}30`,
                  display: "flex", alignItems: "center", justifyContent: "center",
                }}>
                  <Icon name={m.includes("Local") ? "cpu" : "cloud"} size={16} color={C.accent} />
                </div>
                <div>
                  <div style={{ fontSize: T.fontMd, color: C.text }}>{m}</div>
                  <div style={{ fontSize: "10px", color: C.textC }}>
                    {m.includes("Local") ? "على الجهاز — لا يحتاج إنترنت" : "سحابي"}
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
              <Icon name={d.icon} size={17} color={d.danger ? C.danger : C.textB} />
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
