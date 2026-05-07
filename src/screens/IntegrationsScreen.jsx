import C from "../theme/colors.js";
import T from "../theme/typography.js";
import R from "../theme/radius.js";
import Icon from "../components/Icon.jsx";
import ScreenHeader from "../components/ScreenHeader.jsx";

const APPS = [
  {
    name:  "جيميل",
    icon:  "mail",
    color: "#ea4335",
    bg:    "#ea433522",
    desc:  "أنشئ ردوداً، وابحث في بريدك الوارد، وخصص ملخصات...",
  },
  {
    name:  "جيت هب",
    icon:  "github",
    color: "#f0f0f0",
    bg:    "#f0f0f022",
    desc:  "إدارة المستودعات، تتبع تغييرات الكود، والتعاون...",
  },
  {
    name:  "OpenAI",
    icon:  "openai",
    color: "#10a37f",
    bg:    "#10a37f22",
    desc:  "استخدام سلسلة نماذج GPT لتوليد النصوص وإعالجتها",
  },
];

const IntegrationsScreen = ({ onBack }) => (
  <div style={{ display: "flex", flexDirection: "column", height: "100%", padding: "0 16px" }}>
    <ScreenHeader
      title="التطبيقات المتصلة"
      onBack={onBack}
      right={
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Icon name="plus" size={22} color={C.accent} />
          <Icon name="arrowRight" size={20} color={C.textB} />
        </div>
      }
    />

    <div style={{ flex: 1, overflowY: "auto" }}>
      {APPS.map((a, i) => (
        <div
          key={i}
          style={{
            background: C.surface, borderRadius: R.radius, padding: "16px",
            marginBottom: 12, border: `1px solid ${C.border}`, cursor: "pointer",
            display: "flex", alignItems: "center", gap: 14,
          }}
        >
          <div style={{
            width: 46, height: 46, borderRadius: 12,
            background: a.bg,
            display: "flex", alignItems: "center", justifyContent: "center",
            flexShrink: 0,
          }}>
            <Icon name={a.icon} size={22} color={a.color} />
          </div>
          <div style={{ flex: 1, textAlign: "right" }}>
            <div style={{ fontSize: T.fontLg, color: C.text, fontWeight: 600 }}>{a.name}</div>
            <div style={{ fontSize: T.fontSm, color: C.textB, marginTop: 4, lineHeight: 1.4 }}>{a.desc}</div>
          </div>
          <Icon name="arrowLeft" size={16} color={C.textC} style={{ transform: "rotate(180deg)" }} />
        </div>
      ))}
    </div>
  </div>
);

export default IntegrationsScreen;
