import C from "../theme/colors.js";
import T from "../theme/typography.js";
import Icon from "../components/Icon.jsx";
import ScreenHeader from "../components/ScreenHeader.jsx";
import SectionCard from "../components/SectionCard.jsx";

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

const PREF_ROWS = [
  { icon: "globe",   label: "اللغة",                  value: "العربية"       },
  { icon: "monitor", label: "المظهر",                  value: "اتباع النظام" },
  { icon: "trash",   label: "مسح ذاكرة التخزين المؤقت", value: "90 ..."      },
];

const EXTRA_ROWS = [
  { icon: "files", label: "Playbook",       external: true },
  { icon: "star",  label: "قيم هذا التطبيق", external: true },
];

const SettingsScreen = ({ onBack, onNav }) => (
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
          style={{
            display: "flex", alignItems: "center", gap: 12, padding: "15px 0",
            borderBottom: i < arr.length - 1 ? `1px solid ${C.border}` : "none",
            cursor: "pointer",
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
          <span style={{ fontSize: T.fontSm, color: C.textB }}>{r.value}</span>
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
  </div>
);

export default SettingsScreen;
