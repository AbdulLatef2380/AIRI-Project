import C from "../theme/colors.js";
import T from "../theme/typography.js";
import Icon from "../components/Icon.jsx";
import ScreenHeader from "../components/ScreenHeader.jsx";
import SectionCard from "../components/SectionCard.jsx";

const DATA_ROWS = [
  { icon: "calendar", label: "المهام المشتركة" },
  { icon: "files",    label: "الملفات المشتركة" },
  { icon: "globe",    label: "المواقع الإلكترونية" },
  { icon: "app",      label: "تطبيقات" },
];

const DataControlsScreen = ({ onBack }) => (
  <div style={{ display: "flex", flexDirection: "column", height: "100%", padding: "0 16px" }}>
    <ScreenHeader
      title="ضوابط البيانات"
      onBack={onBack}
      right={<Icon name="arrowRight" size={20} color={C.textB} />}
    />

    <SectionCard>
      {DATA_ROWS.map((r, i, arr) => (
        <div
          key={r.label}
          style={{
            display: "flex", alignItems: "center", gap: 12, padding: "15px 0",
            borderBottom: i < arr.length - 1 ? `1px solid ${C.border}` : "none",
            cursor: "pointer",
          }}
        >
          <div style={{
            width: 38, height: 38, borderRadius: 9,
            background: C.surfaceB,
            display: "flex", alignItems: "center", justifyContent: "center",
          }}>
            <Icon name={r.icon} size={18} color={C.textB} />
          </div>
          <span style={{ flex: 1, fontSize: T.fontMd, color: C.text, textAlign: "right" }}>
            {r.label}
          </span>
          <Icon
            name="arrowLeft"
            size={16}
            color={C.textC}
            style={{ transform: "rotate(180deg)" }}
          />
        </div>
      ))}
    </SectionCard>
  </div>
);

export default DataControlsScreen;
