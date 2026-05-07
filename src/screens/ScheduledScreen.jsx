import { useState } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";
import R from "../theme/radius.js";
import Icon from "../components/Icon.jsx";
import ScreenHeader from "../components/ScreenHeader.jsx";

const TASKS = [
  { title: "تحليل تقرير المبيعات",  time: "يوميًا، 9:00 ص",       status: "scheduled" },
  { title: "مراجعة البريد الإلكتروني", time: "كل ساعة",           status: "scheduled" },
  { title: "نسخ احتياطي للمشروع",    time: "أسبوعيًا، الاثنين",   status: "complete"  },
  { title: "إرسال ملخص أسبوعي",      time: "الجمعة 5:00 م",       status: "complete"  },
];

const ScheduledScreen = ({ onBack }) => {
  const [tab, setTab] = useState("scheduled");

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%", padding: "0 16px" }}>
      <ScreenHeader
        title="المهام"
        onBack={onBack}
        right={
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <Icon name="plus" size={22} color={C.accent} />
            <Icon name="arrowRight" size={20} color={C.textB} />
          </div>
        }
      />

      {/* Tabs */}
      <div style={{
        display: "flex", background: C.surface, borderRadius: 12,
        padding: 4, marginBottom: 16, gap: 4,
      }}>
        {[["scheduled", "مجدول"], ["complete", "مكتمل"]].map(([k, l]) => (
          <div
            key={k}
            onClick={() => setTab(k)}
            style={{
              flex: 1, textAlign: "center", padding: "8px 0", borderRadius: 9,
              cursor: "pointer",
              background: tab === k ? C.accent : "transparent",
              fontSize: T.fontMd,
              color: tab === k ? C.text : C.textB,
              fontWeight: tab === k ? 600 : 400,
              transition: "all .2s",
            }}
          >
            {l}
          </div>
        ))}
      </div>

      {/* Task list */}
      <div style={{ flex: 1, overflowY: "auto" }}>
        {TASKS.filter(t => t.status === tab).map((t, i) => (
          <div
            key={i}
            style={{
              background: C.surface, borderRadius: R.radius, padding: "14px 16px",
              marginBottom: 10, border: `1px solid ${C.border}`,
              display: "flex", alignItems: "center", gap: 12,
            }}
          >
            <div style={{
              width: 10, height: 10, borderRadius: "50%",
              background: t.status === "complete" ? C.success : C.accent,
              flexShrink: 0,
            }} />
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: T.fontMd, color: C.text, fontWeight: 500 }}>{t.title}</div>
              <div style={{ fontSize: T.fontSm, color: C.textB, marginTop: 3 }}>{t.time}</div>
            </div>
            <Icon name="dots" size={18} color={C.textC} />
          </div>
        ))}
      </div>
    </div>
  );
};

export default ScheduledScreen;
