import { useState } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";
import Icon from "../components/Icon.jsx";
import ScreenHeader from "../components/ScreenHeader.jsx";
import SectionCard from "../components/SectionCard.jsx";
import BottomSheet from "../components/BottomSheet.jsx";
import { useApp } from "../context/useApp.js";
import { useProvider } from "../hooks/useProvider.js";

const NAV_ROWS = [
  { icon: "calendar",  label: "المهام المجدولة",  screen: "scheduled"    },
  { icon: "book",      label: "معرفة",             screen: "knowledge"    },
  { icon: "mail",      label: "بريد Airi",         badge: "جديد"          },
  { icon: "settings",  label: "ضوابط البيانات",    screen: "data"         },
  { icon: "globe",     label: "متصفح السحابة"                             },
  { icon: "skill",     label: "المهارات",           screen: "skills"       },
  { icon: "connector", label: "الموصلات",           screen: "connectors"   },
  { icon: "plug",      label: "التكاملات",          screen: "integrations" },
];

const EXTRA_ROWS = [
  { icon: "files", label: "Playbook",       external: true },
  { icon: "star",  label: "قيم هذا التطبيق", external: true },
];

const SettingsScreen = ({ onBack, onNav }) => {
  const { clearCache }   = useApp();
  const { apiKeys, activeModel, saveOpenAIKey, saveAnthropicKey, localEndpoint, saveLocalEndpoint } = useProvider();

  const [showApiKeys, setShowApiKeys] = useState(false);
  const [openaiVal,   setOpenaiVal]   = useState(apiKeys?.openai    ?? "");
  const [anthropicVal, setAnthropicVal] = useState(apiKeys?.anthropic ?? "");
  const [endpointVal,  setEndpointVal]  = useState(localEndpoint ?? "http://localhost:11434");
  const [cleared, setCleared] = useState(false);

  const handleClearCache = () => {
    clearCache();
    setCleared(true);
    setTimeout(() => setCleared(false), 2000);
  };

  const handleSaveApiKeys = () => {
    saveOpenAIKey(openaiVal);
    saveAnthropicKey(anthropicVal);
    saveLocalEndpoint(endpointVal);
    setShowApiKeys(false);
  };

  const PREF_ROWS = [
    { icon: "globe",   label: "اللغة",                    value: "العربية"                            },
    { icon: "monitor", label: "المظهر",                    value: "اتباع النظام"                       },
    {
      icon: "settings", label: "مفاتيح API",              value: activeModel, action: () => setShowApiKeys(true),
    },
    {
      icon: "trash",   label: "مسح ذاكرة التخزين المؤقت", value: cleared ? "✓ تم المسح" : "90 ...",
      action: handleClearCache,
    },
  ];

  return (
    <div style={{
      display: "flex", flexDirection: "column", height: "100%",
      padding: "0 16px", overflowY: "auto",
    }}>
      <ScreenHeader
        title="الإعدادات"
        onBack={onBack}
        right={<Icon name="arrowRight" size={20} color={C.textB} />}
      />

      {/* Navigation rows */}
      <SectionCard>
        {NAV_ROWS.map((r, i, arr) => (
          <div
            key={r.label}
            onClick={() => r.screen && onNav && onNav(r.screen)}
            style={{
              display: "flex", alignItems: "center", gap: 12, padding: "15px 0",
              borderBottom: i < arr.length - 1 ? `1px solid ${C.border}` : "none",
              cursor: r.screen ? "pointer" : "default",
            }}
          >
            <Icon
              name="arrowLeft"
              size={16}
              color={C.textC}
              style={{ transform: "rotate(180deg)" }}
            />
            <span style={{ flex: 1, fontSize: T.fontMd, color: C.text, textAlign: "right" }}>
              {r.label}
            </span>
            {r.badge && (
              <div style={{
                fontSize: "10px", color: C.accent,
                background: `${C.accent}22`, borderRadius: 6, padding: "2px 7px",
              }}>
                {r.badge}
              </div>
            )}
            <Icon name={r.icon} size={18} color={C.textB} />
          </div>
        ))}
      </SectionCard>

      {/* Preferences rows */}
      <SectionCard>
        {PREF_ROWS.map((r, i, arr) => (
          <div
            key={r.label}
            onClick={r.action}
            style={{
              display: "flex", alignItems: "center", gap: 12, padding: "15px 0",
              borderBottom: i < arr.length - 1 ? `1px solid ${C.border}` : "none",
              cursor: r.action ? "pointer" : "default",
            }}
          >
            <Icon
              name="arrowLeft"
              size={16}
              color={C.textC}
              style={{ transform: "rotate(180deg)" }}
            />
            <span style={{ flex: 1, fontSize: T.fontMd, color: C.text, textAlign: "right" }}>
              {r.label}
            </span>
            <span style={{ fontSize: T.fontSm, color: r.label.includes("مسح") && cleared ? C.accent : C.textB }}>
              {r.value}
            </span>
            <Icon name={r.icon} size={18} color={C.textB} />
          </div>
        ))}
      </SectionCard>

      {/* External rows */}
      <SectionCard>
        {EXTRA_ROWS.map((r, i, arr) => (
          <div
            key={r.label}
            style={{
              display: "flex", alignItems: "center", gap: 12, padding: "15px 0",
              borderBottom: i < arr.length - 1 ? `1px solid ${C.border}` : "none",
              cursor: "pointer",
            }}
          >
            <Icon
              name={r.external ? "arrowRight" : "arrowLeft"}
              size={16}
              color={C.textC}
              style={!r.external ? { transform: "rotate(180deg)" } : {}}
            />
            <span style={{ flex: 1, fontSize: T.fontMd, color: C.text, textAlign: "right" }}>
              {r.label}
            </span>
            <Icon name={r.icon} size={18} color={C.textB} />
          </div>
        ))}
      </SectionCard>

      <div style={{ height: 20 }} />

      {/* API Keys Sheet */}
      {showApiKeys && (
        <BottomSheet title="مفاتيح API" onClose={() => setShowApiKeys(false)}>
          <div style={{ display: "flex", flexDirection: "column", gap: 14, paddingBottom: 8 }}>

            {/* OpenAI */}
            <div>
              <div style={{ fontSize: T.fontSm, color: C.textB, textAlign: "right", marginBottom: 6 }}>
                OpenAI API Key
              </div>
              <div style={{
                background: C.surfaceB, borderRadius: 10, padding: "11px 14px",
                border: `1px solid ${C.border}`,
              }}>
                <input
                  type="password"
                  value={openaiVal}
                  onChange={e => setOpenaiVal(e.target.value)}
                  placeholder="sk-..."
                  style={{
                    width: "100%", background: "transparent", border: "none", outline: "none",
                    color: C.text, fontSize: T.fontSm, fontFamily: "monospace",
                    direction: "ltr", textAlign: "left",
                  }}
                />
              </div>
            </div>

            {/* Anthropic */}
            <div>
              <div style={{ fontSize: T.fontSm, color: C.textB, textAlign: "right", marginBottom: 6 }}>
                Anthropic API Key
              </div>
              <div style={{
                background: C.surfaceB, borderRadius: 10, padding: "11px 14px",
                border: `1px solid ${C.border}`,
              }}>
                <input
                  type="password"
                  value={anthropicVal}
                  onChange={e => setAnthropicVal(e.target.value)}
                  placeholder="sk-ant-..."
                  style={{
                    width: "100%", background: "transparent", border: "none", outline: "none",
                    color: C.text, fontSize: T.fontSm, fontFamily: "monospace",
                    direction: "ltr", textAlign: "left",
                  }}
                />
              </div>
            </div>

            {/* Local endpoint */}
            <div>
              <div style={{ fontSize: T.fontSm, color: C.textB, textAlign: "right", marginBottom: 6 }}>
                Local Model Endpoint (Ollama)
              </div>
              <div style={{
                background: C.surfaceB, borderRadius: 10, padding: "11px 14px",
                border: `1px solid ${C.border}`,
              }}>
                <input
                  type="text"
                  value={endpointVal}
                  onChange={e => setEndpointVal(e.target.value)}
                  placeholder="http://localhost:11434"
                  style={{
                    width: "100%", background: "transparent", border: "none", outline: "none",
                    color: C.text, fontSize: T.fontSm, fontFamily: "monospace",
                    direction: "ltr", textAlign: "left",
                  }}
                />
              </div>
            </div>

            <div style={{ display: "flex", gap: 10 }}>
              <div
                onClick={handleSaveApiKeys}
                style={{
                  flex: 1, background: C.accent, borderRadius: 12, padding: "12px 0",
                  textAlign: "center", cursor: "pointer",
                }}
              >
                <span style={{ fontSize: T.fontMd, color: "white", fontWeight: 600 }}>حفظ</span>
              </div>
              <div
                onClick={() => setShowApiKeys(false)}
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
      )}
    </div>
  );
};

export default SettingsScreen;
