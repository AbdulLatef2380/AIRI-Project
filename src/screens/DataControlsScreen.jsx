import { useState } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";
import Icon from "../components/Icon.jsx";
import ScreenHeader from "../components/ScreenHeader.jsx";
import SectionCard from "../components/SectionCard.jsx";
import BottomSheet from "../components/BottomSheet.jsx";
import { useApp } from "../context/useApp.js";

const DATA_ROWS = [
  { icon: "calendar", label: "المهام المشتركة",   key: "scheduled" },
  { icon: "files",    label: "الملفات المشتركة",  key: "files"     },
  { icon: "globe",    label: "المواقع الإلكترونية", key: "sites"   },
  { icon: "app",      label: "تطبيقات",            key: "apps"     },
];

const DataControlsScreen = ({ onBack }) => {
  const { conversations, deleteConversation, clearCache } = useApp();

  const [showExport, setShowExport]   = useState(false);
  const [showDelete, setShowDelete]   = useState(false);
  const [exported,   setExported]     = useState(false);
  const [deleted,    setDeleted]      = useState(false);

  const handleExport = () => {
    const data = {
      exportedAt:    new Date().toISOString(),
      conversations: conversations.map(c => ({
        id:        c.id,
        model:     c.model,
        updatedAt: c.updatedAt,
        messages:  c.messages,
      })),
    };
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement("a");
    a.href     = url;
    a.download = `airi_export_${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
    setExported(true);
    setShowExport(false);
    setTimeout(() => setExported(false), 3000);
  };

  const handleDeleteAll = () => {
    conversations.forEach(c => deleteConversation(c.id));
    setDeleted(true);
    setShowDelete(false);
    setTimeout(() => setDeleted(false), 3000);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%", padding: "0 16px" }}>
      <ScreenHeader
        title="ضوابط البيانات"
        onBack={onBack}
        right={<Icon name="arrowRight" size={20} color={C.textB} />}
      />

      {/* Feedback banners */}
      {exported && (
        <div style={{
          background: `${C.success}22`, border: `1px solid ${C.success}44`,
          borderRadius: 10, padding: "10px 14px", marginBottom: 12, textAlign: "right",
        }}>
          <span style={{ fontSize: T.fontSm, color: C.success }}>✓ تم تصدير البيانات بنجاح</span>
        </div>
      )}
      {deleted && (
        <div style={{
          background: `${C.accent}22`, border: `1px solid ${C.accent}44`,
          borderRadius: 10, padding: "10px 14px", marginBottom: 12, textAlign: "right",
        }}>
          <span style={{ fontSize: T.fontSm, color: C.accent }}>✓ تم حذف المحادثات</span>
        </div>
      )}

      {/* Data categories */}
      <SectionCard style={{ marginBottom: 16 }}>
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

      {/* Conversation stats */}
      <SectionCard style={{ marginBottom: 16 }}>
        <div style={{
          display: "flex", alignItems: "center", justifyContent: "space-between",
          padding: "14px 0",
        }}>
          <span style={{ fontSize: T.fontSm, color: C.textB }}>
            {conversations.length} محادثة
          </span>
          <span style={{ fontSize: T.fontMd, color: C.text, textAlign: "right", fontWeight: 500 }}>
            المحادثات المحفوظة
          </span>
        </div>
      </SectionCard>

      {/* Action buttons */}
      <SectionCard>
        <div
          onClick={() => setShowExport(true)}
          style={{
            display: "flex", alignItems: "center", gap: 12, padding: "15px 0",
            borderBottom: `1px solid ${C.border}`, cursor: "pointer",
          }}
        >
          <div style={{
            width: 38, height: 38, borderRadius: 9,
            background: `${C.accent}22`,
            display: "flex", alignItems: "center", justifyContent: "center",
          }}>
            <Icon name="upload" size={18} color={C.accent} />
          </div>
          <span style={{ flex: 1, fontSize: T.fontMd, color: C.text, textAlign: "right" }}>
            تصدير بياناتي
          </span>
          <Icon name="arrowLeft" size={16} color={C.textC} style={{ transform: "rotate(180deg)" }} />
        </div>

        <div
          onClick={() => setShowDelete(true)}
          style={{
            display: "flex", alignItems: "center", gap: 12, padding: "15px 0",
            borderBottom: `1px solid ${C.border}`, cursor: "pointer",
          }}
        >
          <div style={{
            width: 38, height: 38, borderRadius: 9,
            background: `${C.danger}22`,
            display: "flex", alignItems: "center", justifyContent: "center",
          }}>
            <Icon name="trash" size={18} color={C.danger} />
          </div>
          <span style={{ flex: 1, fontSize: T.fontMd, color: C.danger, textAlign: "right" }}>
            حذف جميع المحادثات
          </span>
          <Icon name="arrowLeft" size={16} color={C.textC} style={{ transform: "rotate(180deg)" }} />
        </div>

        <div
          onClick={clearCache}
          style={{
            display: "flex", alignItems: "center", gap: 12, padding: "15px 0",
            cursor: "pointer",
          }}
        >
          <div style={{
            width: 38, height: 38, borderRadius: 9,
            background: `${C.danger}11`,
            display: "flex", alignItems: "center", justifyContent: "center",
          }}>
            <Icon name="trash" size={18} color={C.danger} />
          </div>
          <span style={{ flex: 1, fontSize: T.fontMd, color: C.danger, textAlign: "right" }}>
            إعادة ضبط كل الإعدادات
          </span>
          <Icon name="arrowLeft" size={16} color={C.textC} style={{ transform: "rotate(180deg)" }} />
        </div>
      </SectionCard>

      {/* Export confirm sheet */}
      {showExport && (
        <BottomSheet title="تصدير البيانات" onClose={() => setShowExport(false)}>
          <div style={{ paddingBottom: 8 }}>
            <div style={{ fontSize: T.fontSm, color: C.textB, textAlign: "right", marginBottom: 18, lineHeight: 1.6 }}>
              سيتم تصدير جميع المحادثات المحفوظة ({conversations.length}) كملف JSON إلى جهازك.
            </div>
            <div style={{ display: "flex", gap: 10 }}>
              <div
                onClick={handleExport}
                style={{
                  flex: 1, background: C.accent, borderRadius: 12, padding: "13px 0",
                  textAlign: "center", cursor: "pointer",
                }}
              >
                <span style={{ fontSize: T.fontMd, color: "white", fontWeight: 600 }}>تصدير</span>
              </div>
              <div
                onClick={() => setShowExport(false)}
                style={{
                  flex: 1, background: C.surfaceB, borderRadius: 12, padding: "13px 0",
                  textAlign: "center", border: `1px solid ${C.border}`, cursor: "pointer",
                }}
              >
                <span style={{ fontSize: T.fontMd, color: C.textB }}>إلغاء</span>
              </div>
            </div>
          </div>
        </BottomSheet>
      )}

      {/* Delete confirm sheet */}
      {showDelete && (
        <BottomSheet title="تأكيد الحذف" onClose={() => setShowDelete(false)}>
          <div style={{ paddingBottom: 8 }}>
            <div style={{ fontSize: T.fontSm, color: C.textB, textAlign: "right", marginBottom: 18, lineHeight: 1.6 }}>
              هل أنت متأكد؟ سيتم حذف {conversations.length} محادثة نهائيًا ولا يمكن التراجع.
            </div>
            <div style={{ display: "flex", gap: 10 }}>
              <div
                onClick={handleDeleteAll}
                style={{
                  flex: 1, background: C.danger, borderRadius: 12, padding: "13px 0",
                  textAlign: "center", cursor: "pointer",
                }}
              >
                <span style={{ fontSize: T.fontMd, color: "white", fontWeight: 600 }}>حذف</span>
              </div>
              <div
                onClick={() => setShowDelete(false)}
                style={{
                  flex: 1, background: C.surfaceB, borderRadius: 12, padding: "13px 0",
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

export default DataControlsScreen;
