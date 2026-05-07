import { useState } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";
import R from "../theme/radius.js";
import Icon from "../components/Icon.jsx";
import ScreenHeader from "../components/ScreenHeader.jsx";
import BottomSheet from "../components/BottomSheet.jsx";
import { useScheduled } from "../hooks/useScheduled.js";

const EMPTY_FORM = { title: "", time: "" };

const ScheduledScreen = ({ onBack }) => {
  const [tab, setTab]         = useState("scheduled");
  const [showAdd, setShowAdd] = useState(false);
  const [form, setForm]       = useState(EMPTY_FORM);
  const [showDots, setShowDots] = useState(null);

  const { scheduled, completed, addTask, completeTask, deleteTask } = useScheduled();
  const items = tab === "scheduled" ? scheduled : completed;

  const handleSave = () => {
    if (!form.title.trim()) return;
    addTask(form.title, form.time);
    setForm(EMPTY_FORM);
    setShowAdd(false);
  };

  const handleDotAction = (action, id) => {
    if (action === "complete") completeTask(id);
    if (action === "delete")   deleteTask(id);
    setShowDots(null);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%", padding: "0 16px" }}>
      <ScreenHeader
        title="المهام"
        onBack={onBack}
        right={
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <div onClick={() => setShowAdd(true)} style={{ cursor: "pointer" }}>
              <Icon name="plus" size={22} color={C.accent} />
            </div>
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
        {items.length === 0 && (
          <div style={{
            display: "flex", flexDirection: "column", alignItems: "center",
            justifyContent: "center", paddingTop: 60, gap: 12,
          }}>
            <Icon name="calendar" size={40} color={C.textC} />
            <span style={{ fontSize: T.fontMd, color: C.textC }}>
              {tab === "scheduled" ? "لا توجد مهام مجدولة" : "لا توجد مهام مكتملة"}
            </span>
            {tab === "scheduled" && (
              <div
                onClick={() => setShowAdd(true)}
                style={{
                  background: C.accent, borderRadius: 12, padding: "10px 20px",
                  cursor: "pointer", marginTop: 8,
                }}
              >
                <span style={{ fontSize: T.fontMd, color: "white", fontWeight: 600 }}>
                  إضافة مهمة
                </span>
              </div>
            )}
          </div>
        )}

        {items.map(t => (
          <div
            key={t.id}
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
              <div style={{
                fontSize: T.fontMd, color: C.text, fontWeight: 500,
                textDecoration: t.status === "complete" ? "line-through" : "none",
                opacity: t.status === "complete" ? 0.6 : 1,
              }}>
                {t.title}
              </div>
              <div style={{ fontSize: T.fontSm, color: C.textB, marginTop: 3 }}>{t.time}</div>
            </div>
            <div onClick={() => setShowDots(t.id)} style={{ cursor: "pointer", padding: 4 }}>
              <Icon name="dots" size={18} color={C.textC} />
            </div>
          </div>
        ))}
      </div>

      {/* Add task sheet */}
      {showAdd && (
        <BottomSheet title="إضافة مهمة" onClose={() => { setShowAdd(false); setForm(EMPTY_FORM); }}>
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <div>
              <div style={{ fontSize: T.fontSm, color: C.textB, marginBottom: 6, textAlign: "right" }}>
                عنوان المهمة <span style={{ color: C.danger }}>*</span>
              </div>
              <input
                autoFocus
                placeholder="مثال: تحليل تقرير المبيعات"
                value={form.title}
                onChange={e => setForm(f => ({ ...f, title: e.target.value }))}
                style={{
                  width: "100%", background: C.surfaceB, border: `1px solid ${C.border}`,
                  borderRadius: 10, padding: "12px 14px", color: C.text,
                  fontSize: T.fontMd, outline: "none", fontFamily: "inherit",
                  direction: "rtl", boxSizing: "border-box",
                }}
              />
            </div>
            <div>
              <div style={{ fontSize: T.fontSm, color: C.textB, marginBottom: 6, textAlign: "right" }}>
                الجدول الزمني
              </div>
              <input
                placeholder="مثال: يوميًا 9:00 ص"
                value={form.time}
                onChange={e => setForm(f => ({ ...f, time: e.target.value }))}
                style={{
                  width: "100%", background: C.surfaceB, border: `1px solid ${C.border}`,
                  borderRadius: 10, padding: "12px 14px", color: C.text,
                  fontSize: T.fontMd, outline: "none", fontFamily: "inherit",
                  direction: "rtl", boxSizing: "border-box",
                }}
              />
            </div>
            <div style={{ display: "flex", gap: 10, marginTop: 4 }}>
              <div
                onClick={handleSave}
                style={{
                  flex: 1, background: form.title.trim() ? C.accent : C.surfaceC,
                  borderRadius: 12, padding: "14px 0", textAlign: "center",
                  cursor: form.title.trim() ? "pointer" : "default",
                }}
              >
                <span style={{ fontSize: T.fontMd, color: "white", fontWeight: 700 }}>إضافة</span>
              </div>
              <div
                onClick={() => { setShowAdd(false); setForm(EMPTY_FORM); }}
                style={{
                  flex: 1, background: C.surfaceB, borderRadius: 12, padding: "14px 0",
                  textAlign: "center", border: `1px solid ${C.border}`, cursor: "pointer",
                }}
              >
                <span style={{ fontSize: T.fontMd, color: C.textB }}>إلغاء</span>
              </div>
            </div>
          </div>
        </BottomSheet>
      )}

      {/* Task context menu */}
      {showDots !== null && (
        <BottomSheet title="" onClose={() => setShowDots(null)} compact>
          {tab === "scheduled" && (
            <div
              onClick={() => handleDotAction("complete", showDots)}
              style={{
                display: "flex", alignItems: "center", gap: 12,
                padding: "13px 0", borderBottom: `1px solid ${C.border}`, cursor: "pointer",
              }}
            >
              <Icon name="check" size={18} color={C.success} />
              <span style={{ fontSize: T.fontMd, color: C.text }}>تحديد كمكتمل</span>
            </div>
          )}
          <div
            onClick={() => handleDotAction("delete", showDots)}
            style={{
              display: "flex", alignItems: "center", gap: 12,
              padding: "13px 0", cursor: "pointer",
            }}
          >
            <Icon name="trash" size={18} color={C.danger} />
            <span style={{ fontSize: T.fontMd, color: C.danger }}>حذف المهمة</span>
          </div>
        </BottomSheet>
      )}
    </div>
  );
};

export default ScheduledScreen;
