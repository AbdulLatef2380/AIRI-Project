import { useState } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";
import Icon from "../components/Icon.jsx";
import Toggle from "../components/Toggle.jsx";
import ScreenHeader from "../components/ScreenHeader.jsx";
import SectionCard from "../components/SectionCard.jsx";
import BottomSheet from "../components/BottomSheet.jsx";
import { useConnectors } from "../hooks/useConnectors.js";

const ConnectorsScreen = ({ onBack }) => {
  const [showAdd, setShowAdd] = useState(false);
  const { connected, available, toggle } = useConnectors();

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
              key={c.id}
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
              <Toggle on={c.enabled} onChange={() => toggle(c.id)} />
            </div>
          ))}
        </SectionCard>

        {/* Available section */}
        <div style={{ fontSize: T.fontSm, color: C.textB, marginBottom: 10, textAlign: "right" }}>
          متاحة للاتصال
        </div>
        <SectionCard>
          {available.map((c, i, arr) => (
            <div
              key={c.id}
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
              <div
                onClick={() => toggle(c.id)}
                style={{ background: C.accent, borderRadius: 8, padding: "4px 12px", cursor: "pointer" }}
              >
                <span style={{ fontSize: "12px", color: "white", fontWeight: 600 }}>اتصال</span>
              </div>
            </div>
          ))}
        </SectionCard>

        {/* Action buttons */}
        <div style={{ display: "flex", gap: 10, marginTop: 12 }}>
          <div
            onClick={() => setShowAdd(true)}
            style={{
              flex: 1, background: C.surface, borderRadius: 12, padding: "13px 0",
              display: "flex", alignItems: "center", justifyContent: "center", gap: 6,
              border: `1px solid ${C.border}`, cursor: "pointer",
            }}
          >
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
            <div
              onClick={() => setShowAdd(false)}
              style={{
                background: C.surfaceB, borderRadius: 10, padding: "13px 16px",
                border: `1px solid ${C.border}`, cursor: "pointer",
              }}
            >
              <div style={{ fontSize: T.fontMd, color: C.text, fontWeight: 500, textAlign: "right", marginBottom: 4 }}>
                تطبيقات الموصل
              </div>
              <div style={{ fontSize: T.fontSm, color: C.textB, textAlign: "right" }}>
                اختر من قائمة التطبيقات المتاحة
              </div>
            </div>
            <div
              onClick={() => setShowAdd(false)}
              style={{
                background: C.surfaceB, borderRadius: 10, padding: "13px 16px",
                border: `1px solid ${C.border}`, cursor: "pointer",
              }}
            >
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
