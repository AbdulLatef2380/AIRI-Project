import { useState } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";
import R from "../theme/radius.js";
import Icon from "../components/Icon.jsx";
import ScreenHeader from "../components/ScreenHeader.jsx";
import BottomSheet from "../components/BottomSheet.jsx";

const ENTRIES = [
  {
    title:   "تفضيلات هيكلة الملفات والتبعيات لوحدة 'core'...",
    preview: "يجب أن تكون الملفات الخاصة بوحدة 'core' في المسار...",
    date:    "٤/٨",
    active:  true,
  },
  {
    title:   "تفضيلات أولوية دمج المعلومات من المرفقات ا...",
    preview: "عندما يشدد المستخدم على أهمية مرفق معين...",
    date:    "١/١٤",
    active:  true,
  },
  {
    title:   "تفضيلات تطوير مستند الأخلاقيات والسلامة لـ...",
    preview: "عند تطوير مستند الأخلاقيات والسلامة...",
    date:    "١/١٢",
    active:  true,
  },
  {
    title:   "تفضيلات البحث الاستباقي وإثراء أداة QB-To...",
    preview: "عند تطوير أو تعديل أداة QB-Tools أو أي أداة...",
    date:    "٢٠٢٥/١٢/١٤",
    active:  true,
  },
];

const EMPTY_FORM = { name: "", when: "", content: "" };

const KnowledgeScreen = ({ onBack }) => {
  const [showAdd, setShowAdd] = useState(false);
  const [form, setForm]       = useState(EMPTY_FORM);

  const handleSave = () => {
    setShowAdd(false);
    setForm(EMPTY_FORM);
  };

  return (
    <div style={{
      display: "flex", flexDirection: "column", height: "100%",
      padding: "0 16px", position: "relative",
    }}>
      <ScreenHeader
        title="معرفة"
        onBack={onBack}
        right={
          <div onClick={() => setShowAdd(true)} style={{ cursor: "pointer" }}>
            <Icon name="plus" size={22} color={C.accent} />
          </div>
        }
      />

      {/* Entry list */}
      <div style={{ flex: 1, overflowY: "auto" }}>
        {ENTRIES.map((e, i) => (
          <div
            key={i}
            style={{
              background: C.surface, borderRadius: R.radius, padding: "14px 16px",
              marginBottom: 10, border: `1px solid ${C.border}`, cursor: "pointer",
            }}
          >
            <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 8 }}>
              <div style={{ flex: 1, fontSize: T.fontMd, color: C.text, fontWeight: 500, lineHeight: 1.4 }}>
                {e.title}
              </div>
            </div>
            <div style={{ fontSize: T.fontSm, color: C.textB, marginTop: 6, lineHeight: 1.5 }}>
              {e.preview}
            </div>
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 10 }}>
              <span style={{ fontSize: "11px", color: C.textC }}>{e.date}</span>
              <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                <div style={{ width: 7, height: 7, borderRadius: "50%", background: C.accent }} />
                <span style={{ fontSize: "11px", color: C.accent }}>مفعل</span>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Add knowledge sheet */}
      {showAdd && (
        <BottomSheet title="إضافة معرفة" onClose={() => setShowAdd(false)}>
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            {/* Name */}
            <div>
              <div style={{ fontSize: T.fontSm, color: C.textB, marginBottom: 6, textAlign: "right" }}>
                الاسم
              </div>
              <input
                placeholder="اسم المعرفة"
                value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                style={{
                  width: "100%", background: C.surfaceB, border: `1px solid ${C.border}`,
                  borderRadius: 10, padding: "12px 14px", color: C.text,
                  fontSize: T.fontMd, outline: "none", fontFamily: "inherit",
                  direction: "rtl", boxSizing: "border-box",
                }}
              />
            </div>

            {/* When */}
            <div>
              <div style={{ fontSize: T.fontSm, color: C.textB, marginBottom: 6, textAlign: "right" }}>
                استخدم عندما <span style={{ color: C.danger }}>*</span>
              </div>
              <input
                placeholder="متى تُستخدم هذه المعرفة"
                value={form.when}
                onChange={e => setForm(f => ({ ...f, when: e.target.value }))}
                style={{
                  width: "100%", background: C.surfaceB, border: `1px solid ${C.border}`,
                  borderRadius: 10, padding: "12px 14px", color: C.text,
                  fontSize: T.fontMd, outline: "none", fontFamily: "inherit",
                  direction: "rtl", boxSizing: "border-box",
                }}
              />
            </div>

            {/* Content */}
            <div>
              <div style={{ fontSize: T.fontSm, color: C.textB, marginBottom: 6, textAlign: "right" }}>
                المحتوى <span style={{ color: C.danger }}>*</span>
              </div>
              <textarea
                placeholder="محتوى المعرفة"
                value={form.content}
                onChange={e => setForm(f => ({ ...f, content: e.target.value }))}
                rows={4}
                style={{
                  width: "100%", background: C.surfaceB, border: `1px solid ${C.border}`,
                  borderRadius: 10, padding: "12px 14px", color: C.text,
                  fontSize: T.fontMd, outline: "none", fontFamily: "inherit",
                  direction: "rtl", resize: "none", boxSizing: "border-box",
                }}
              />
            </div>

            {/* Save */}
            <div
              onClick={handleSave}
              style={{
                background: C.accent, borderRadius: 12, padding: "14px 0",
                display: "flex", alignItems: "center", justifyContent: "center",
                cursor: "pointer", marginTop: 4,
              }}
            >
              <span style={{ fontSize: T.fontMd, color: "white", fontWeight: 700 }}>حفظ</span>
            </div>
          </div>
        </BottomSheet>
      )}
    </div>
  );
};

export default KnowledgeScreen;
