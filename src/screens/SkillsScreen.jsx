import { useState } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";
import R from "../theme/radius.js";
import Icon from "../components/Icon.jsx";
import Toggle from "../components/Toggle.jsx";
import ScreenHeader from "../components/ScreenHeader.jsx";
import SectionCard from "../components/SectionCard.jsx";
import BottomSheet from "../components/BottomSheet.jsx";
import { useSkills } from "../hooks/useSkills.js";

const CREATE_OPTIONS = [
  { icon: "bot",     label: "البناء باستخدام Airi",      sub: "قم ببناء مهارات رائعة من خلال المحادثة",    color: "#4e8cff" },
  { icon: "upload",  label: "رفع مهارة",                  sub: "رفع .skill, .zip",                            color: "#52c4e0" },
  { icon: "library", label: "إضافة من المكتبة الرسمية",   sub: "مهارات جاهزة يتم صيانتها بواسطة Airi",      color: "#4ecca3" },
  { icon: "github",  label: "استيراد من GitHub",          sub: "الصق رابط المستودع للبدء",                   color: "#f0f0f0" },
];

const SkillsScreen = ({ onBack }) => {
  const [showCreate, setShowCreate] = useState(false);
  const { filtered, search, setSearch, toggle } = useSkills();

  return (
    <div style={{
      display: "flex", flexDirection: "column", height: "100%",
      padding: "0 16px", position: "relative",
    }}>
      <ScreenHeader
        title="المهارات"
        onBack={onBack}
        right={
          <div onClick={() => setShowCreate(true)} style={{ cursor: "pointer" }}>
            <Icon name="plus" size={22} color={C.accent} />
          </div>
        }
      />

      {/* Search + filter */}
      <div style={{ display: "flex", gap: 10, marginBottom: 14 }}>
        <div style={{
          flex: 1, display: "flex", alignItems: "center", gap: 8,
          background: C.surface, borderRadius: 12, padding: "10px 14px",
          border: `1px solid ${C.border}`,
        }}>
          <Icon name="search" size={16} color={C.textC} />
          <input
            placeholder="بحث"
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{
              flex: 1, background: "transparent", border: "none", outline: "none",
              color: C.text, fontSize: T.fontMd, direction: "rtl", fontFamily: "inherit",
            }}
          />
        </div>
        <div style={{
          width: 44, height: 44, borderRadius: 12, background: C.surface,
          border: `1px solid ${C.border}`, display: "flex", alignItems: "center",
          justifyContent: "center", cursor: "pointer",
        }}>
          <Icon name="filter" size={18} color={C.textB} />
        </div>
      </div>

      {/* Official library shortcut */}
      <SectionCard style={{ marginBottom: 14 }}>
        <div style={{
          display: "flex", alignItems: "center", gap: 10,
          padding: "14px 0", cursor: "pointer",
        }}>
          <Icon name="library" size={18} color={C.accent} />
          <span style={{ flex: 1, fontSize: T.fontMd, color: C.text, textAlign: "right" }}>
            المكتبة الرسمية
          </span>
          <Icon name="arrowLeft" size={16} color={C.textC} style={{ transform: "rotate(180deg)" }} />
        </div>
      </SectionCard>

      {/* Skills list — real data from SkillRegistry */}
      <div style={{ flex: 1, overflowY: "auto" }}>
        {filtered.map(s => (
          <div
            key={s.id}
            style={{
              background: C.surface, borderRadius: R.radius, padding: "14px 16px",
              marginBottom: 10, border: `1px solid ${C.border}`,
            }}
          >
            <div style={{ display: "flex", alignItems: "flex-start", gap: 10 }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: T.fontMd, color: C.text, fontWeight: 500, textAlign: "right" }}>
                  {s.name}
                </div>
                <div style={{ fontSize: T.fontSm, color: C.textB, marginTop: 4, textAlign: "right", lineHeight: 1.4 }}>
                  {s.desc}
                </div>
              </div>
              <Toggle on={s.on} onChange={() => toggle(s.id)} />
            </div>

            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 10 }}>
              <Icon name="dots" size={18} color={C.textC} />
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                {s.official && (
                  <div style={{ display: "flex", alignItems: "center", gap: 4 }}>
                    <Icon name="check" size={12} color={C.textC} />
                    <span style={{ fontSize: "10px", color: C.textC }}>رسمي</span>
                  </div>
                )}
                <span style={{ fontSize: "11px", color: C.textC }}>{s.date}</span>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Create skill sheet */}
      {showCreate && (
        <BottomSheet title="إنشاء مهارة" onClose={() => setShowCreate(false)}>
          {CREATE_OPTIONS.map(o => (
            <div
              key={o.label}
              onClick={() => setShowCreate(false)}
              style={{
                display: "flex", alignItems: "center", gap: 14,
                padding: "14px 0", borderBottom: `1px solid ${C.border}`, cursor: "pointer",
              }}
            >
              <div style={{
                width: 44, height: 44, borderRadius: 12,
                background: `${o.color}22`,
                display: "flex", alignItems: "center", justifyContent: "center",
                flexShrink: 0,
              }}>
                <Icon name={o.icon} size={20} color={o.color} />
              </div>
              <div style={{ textAlign: "right" }}>
                <div style={{ fontSize: T.fontMd, color: C.text, fontWeight: 500 }}>{o.label}</div>
                <div style={{ fontSize: T.fontSm, color: C.textB, marginTop: 2 }}>{o.sub}</div>
              </div>
            </div>
          ))}
        </BottomSheet>
      )}
    </div>
  );
};

export default SkillsScreen;
