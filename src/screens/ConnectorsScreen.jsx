import { useState } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";
import Icon from "../components/Icon.jsx";
import Toggle from "../components/Toggle.jsx";
import ScreenHeader from "../components/ScreenHeader.jsx";
import SectionCard from "../components/SectionCard.jsx";
import BottomSheet from "../components/BottomSheet.jsx";

const CONNECTED = [
  { name: "Gmail",  icon: "mail",   color: "#ea4335", bg: "#ea433522", on: true },
  { name: "GitHub", icon: "github", color: "#f0f0f0", bg: "#f0f0f022", on: true },
  { name: "OpenAI", icon: "openai", color: "#10a37f", bg: "#10a37f22", on: true },
];

const AVAILABLE = [
  { name: "متصفحي",         icon: "globe",    color: "#4e8cff", bg: "#4e8cff22" },
  { name: "تقويم Google",   icon: "calendar", color: "#4285f4", bg: "#4285f422" },
  { name: "جوجل درايف",    icon: "files",    color: "#fbbc04", bg: "#fbbc0422" },
  { name: "بريد Outlook",  icon: "mail",     color: "#0078d4", bg: "#0078d422" },
  { name: "تقويم Outlook", icon: "calendar", color: "#0078d4", bg: "#0078d422" },
];

const ConnectorsScreen = ({ onBack }) => {
  const [showAdd, setShowAdd]     = useState(false);
  const [connected, setConnected] = useState(CONNECTED);

  const toggleConnected = (index) => {
    setConnected(prev =>
      prev.map((c, i) => i === index ? { ...c, on: !c.on } : c)
    );
  };

  return (
    <div style={{
      display: "flex", flexDirection: "column", height: "100%",
      padding: "0 16px", position: "relative",
    }}>
      <ScreenHeader
        title="الموصلات"
        onBack={onBack}
        right={
          <div onClick={() => setShowAdd(true)} style={{ cursor: "pointer" }}>
            <Icon name="plus" size={22} color={C.accent} />
          </div>
        }
      />

      <div style={{ flex: 1, overflowY: "auto" }}>
        {/* Connected section */}
        <div style={{ fontSize: T.fontSm, color: C.textB, marginBottom: 10, textAlign: "right" }}>
          متصلة
        </div>
        <SectionCard style={{ marginBottom: 18 }}>
          {connected.map((c, i, arr) => (
            <div
              key={c.name}
              style={{
                display: "flex", alignItems: "center", gap: 12, padding: "13px 0",
                borderBottom: i < arr.length - 1 ? `1px solid ${C.border}` : "none",
              }}
            >
              <div style={{
                width: 38, height: 38, borderRadius: 9,
                background: c.bg,
                display: "flex", alignItems: "center", justifyContent: "center",
              }}>
                <Icon name={c.icon} size={18} color={c.color} />
              </div>
              <span style={{ flex: 1, fontSize: T.fontMd, color: C.text, fontWeight: 500, textAlign: "right" }}>
                {c.name}
              </span>
              <Toggle on={c.on} onChange={() => toggleConnected(i)} />
            </div>
          ))}
        </SectionCard>

        {/* Available section */}
        <div style={{ fontSize: T.fontSm, color: C.textB, marginBottom: 10, textAlign: "right" }}>
          متاحة للاتصال
        </div>
        <SectionCard>
          {AVAILABLE.map((c, i, arr) => (
            <div
              key={c.name}
              style={{
                display: "flex", alignItems: "center", gap: 12, padding: "13px 0",
                borderBottom: i < arr.length - 1 ? `1px solid ${C.border}` : "none",
                cursor: "pointer",
              }}
            >
              <div style={{
                width: 38, height: 38, borderRadius: 9,
                background: c.bg,
                display: "flex", alignItems: "center", justifyContent: "center",
              }}>
                <Icon name={c.icon} size={18} color={c.color} />
              </div>
              <span style={{ flex: 1, fontSize: T.fontMd, color: C.text, textAlign: "right" }}>
                {c.name}
              </span>
              <div style={{ background: C.accent, borderRadius: 8, padding: "4px 12px", cursor: "pointer" }}>
                <span style={{ fontSize: "12px", color: "white", fontWeight: 600 }}>اتصال</span>
              </div>
            </div>
          ))}
        </SectionCard>

        {/* Action buttons */}
        <div style={{ display: "flex", gap: 10, marginTop: 12 }}>
          <div style={{
            flex: 1, background: C.surface, borderRadius: 12, padding: "13px 0",
            display: "flex", alignItems: "center", justifyContent: "center", gap: 6,
            border: `1px solid ${C.border}`, cursor: "pointer",
          }}>
            <Icon name="plus" size={16} color={C.accent} />
            <span style={{ fontSize: T.fontMd, color: C.accent }}>إضافة موصلات</span>
          </div>
          <div style={{
            flex: 1, background: C.surface, borderRadius: 12, padding: "13px 0",
            display: "flex", alignItems: "center", justifyContent: "center", gap: 6,
            border: `1px solid ${C.border}`, cursor: "pointer",
          }}>
            <Icon name="settings" size={16} color={C.textB} />
            <span style={{ fontSize: T.fontMd, color: C.textB }}>إدارة الموصلات</span>
          </div>
        </div>
      </div>

      {/* Add connector sheet */}
      {showAdd && (
        <BottomSheet title="إضافة موصل" onClose={() => setShowAdd(false)}>
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <div style={{
              background: C.surfaceB, borderRadius: 10, padding: "13px 16px",
              border: `1px solid ${C.border}`, cursor: "pointer",
            }}>
              <div style={{ fontSize: T.fontMd, color: C.text, fontWeight: 500, textAlign: "right", marginBottom: 4 }}>
                تطبيقات الموصل
              </div>
              <div style={{ fontSize: T.fontSm, color: C.textB, textAlign: "right" }}>
                اختر من قائمة التطبيقات المتاحة
              </div>
            </div>
            <div style={{
              background: C.surfaceB, borderRadius: 10, padding: "13px 16px",
              border: `1px solid ${C.border}`, cursor: "pointer",
            }}>
              <div style={{ fontSize: T.fontMd, color: C.text, fontWeight: 500, textAlign: "right", marginBottom: 4 }}>
                API مخصص
              </div>
              <div style={{ fontSize: T.fontSm, color: C.textB, textAlign: "right" }}>
                أضف موصلًا باستخدام API مخصص
              </div>
            </div>
          </div>
        </BottomSheet>
      )}
    </div>
  );
};

export default ConnectorsScreen;
