import C from "../theme/colors.js";
import T from "../theme/typography.js";
import Icon from "./Icon.jsx";

function relativeTime(ts) {
  if (!ts) return "";
  const diff = Date.now() - ts;
  const mins = Math.floor(diff / 60000);
  if (mins < 1)  return "الآن";
  if (mins < 60) return `منذ ${mins} د`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24)  return `منذ ${hrs} س`;
  const days = Math.floor(hrs / 24);
  if (days < 7)  return `منذ ${days} يوم`;
  return new Date(ts).toLocaleDateString("ar");
}

function preview(conv) {
  const first = conv.messages?.find(m => m.role === "user");
  if (!first?.content) return "محادثة فارغة";
  return first.content.slice(0, 52) + (first.content.length > 52 ? "…" : "");
}

function modelBadgeColor(model) {
  if (!model) return C.textC;
  if (model.includes("Local")) return C.success;
  if (model.includes("Claude")) return "#e0885c";
  return C.accent;
}

const HistorySidebar = ({ conversations, activeConvId, onSelect, onNew, onDelete, onClose }) => {
  const sorted = [...(conversations ?? [])].sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0));

  return (
    <div style={{
      position: "absolute", inset: 0, zIndex: 60,
      display: "flex", flexDirection: "column",
      animation: "fadeIn .18s ease",
    }}>
      {/* Backdrop */}
      <div
        onClick={onClose}
        style={{ position: "absolute", inset: 0, background: "rgba(4,4,12,0.78)" }}
      />

      {/* Slide-in panel from right */}
      <div style={{
        position: "absolute", top: 0, right: 0, bottom: 0,
        width: "82%",
        background: C.surface,
        borderLeft: `1px solid ${C.borderB}`,
        display: "flex", flexDirection: "column",
        animation: "slideInRight .22s cubic-bezier(.25,.8,.25,1)",
        boxShadow: `-8px 0 40px rgba(124,95,255,0.12)`,
      }}>

        {/* Header */}
        <div style={{
          display: "flex", alignItems: "center", justifyContent: "space-between",
          padding: "18px 16px 14px",
          borderBottom: `1px solid ${C.border}`,
          flexShrink: 0,
        }}>
          <div onClick={onClose} style={{ cursor: "pointer", padding: 4 }}>
            <Icon name="x" size={20} color={C.textB} />
          </div>
          <span style={{ fontSize: T.fontXl, fontWeight: 700, color: C.text }}>السجل</span>
          <div style={{ width: 28 }} />
        </div>

        {/* New chat button */}
        <div style={{ padding: "12px 14px", flexShrink: 0 }}>
          <div
            onClick={onNew}
            style={{
              display: "flex", alignItems: "center", gap: 10,
              background: `linear-gradient(135deg, ${C.accent}22, ${C.accentB}11)`,
              border: `1px solid ${C.accent}55`,
              borderRadius: 12, padding: "11px 14px",
              cursor: "pointer", transition: "all .15s",
            }}
          >
            <div style={{
              width: 28, height: 28, borderRadius: 8,
              background: C.accent,
              display: "flex", alignItems: "center", justifyContent: "center",
              flexShrink: 0,
            }}>
              <Icon name="plus" size={16} color="white" />
            </div>
            <span style={{ fontSize: T.fontMd, color: C.accent, fontWeight: 600 }}>
              محادثة جديدة
            </span>
          </div>
        </div>

        {/* Section label */}
        {sorted.length > 0 && (
          <div style={{
            padding: "4px 16px 8px",
            fontSize: "11px", color: C.textC, textAlign: "right",
            letterSpacing: "0.05em",
            flexShrink: 0,
          }}>
            المحادثات السابقة
          </div>
        )}

        {/* Conversation list */}
        <div style={{ flex: 1, overflowY: "auto" }}>
          {sorted.length === 0 && (
            <div style={{
              display: "flex", flexDirection: "column", alignItems: "center",
              justifyContent: "center", paddingTop: 60, gap: 12,
            }}>
              <div style={{
                width: 52, height: 52, borderRadius: "50%",
                background: `${C.accent}15`,
                display: "flex", alignItems: "center", justifyContent: "center",
              }}>
                <Icon name="chat" size={24} color={C.textC} />
              </div>
              <span style={{ fontSize: T.fontSm, color: C.textC }}>لا توجد محادثات سابقة</span>
            </div>
          )}

          {sorted.map(conv => {
            const isActive = conv.id === activeConvId;
            const msgCount = conv.messages?.length ?? 0;
            const badgeColor = modelBadgeColor(conv.model);
            return (
              <div
                key={conv.id}
                style={{
                  position: "relative",
                  margin: "0 10px 4px",
                  borderRadius: 12,
                  background: isActive
                    ? `linear-gradient(135deg, ${C.accent}18, ${C.accentB}0a)`
                    : "transparent",
                  border: isActive
                    ? `1px solid ${C.accent}40`
                    : `1px solid transparent`,
                  transition: "all .15s",
                  cursor: "pointer",
                }}
                onClick={() => onSelect(conv.id)}
              >
                <div style={{ padding: "11px 12px" }}>
                  {/* Top row: preview + time */}
                  <div style={{ display: "flex", alignItems: "flex-start", gap: 6, marginBottom: 6 }}>
                    <span style={{
                      flex: 1, fontSize: T.fontSm, color: C.text, fontWeight: 500,
                      lineHeight: 1.45, textAlign: "right",
                      display: "-webkit-box", WebkitLineClamp: 2,
                      WebkitBoxOrient: "vertical", overflow: "hidden",
                    }}>
                      {preview(conv)}
                    </span>
                  </div>

                  {/* Bottom row: model + count + time + delete */}
                  <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                    <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                      {/* Delete button */}
                      <div
                        onClick={e => { e.stopPropagation(); onDelete(conv.id); }}
                        style={{
                          display: "flex", alignItems: "center", justifyContent: "center",
                          width: 22, height: 22, borderRadius: 6,
                          background: `${C.danger}15`,
                          cursor: "pointer", flexShrink: 0,
                        }}
                      >
                        <Icon name="trash" size={11} color={C.danger} />
                      </div>

                      {/* Message count */}
                      <span style={{ fontSize: "10px", color: C.textC }}>
                        {msgCount} رسالة
                      </span>
                    </div>

                    <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                      {/* Model badge */}
                      {conv.model && (
                        <div style={{
                          fontSize: "9px", color: badgeColor,
                          background: `${badgeColor}18`,
                          borderRadius: 4, padding: "2px 5px",
                          border: `1px solid ${badgeColor}30`,
                        }}>
                          {conv.model}
                        </div>
                      )}
                      {/* Relative time */}
                      <span style={{ fontSize: "10px", color: C.textC }}>
                        {relativeTime(conv.updatedAt)}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            );
          })}

          <div style={{ height: 20 }} />
        </div>
      </div>
    </div>
  );
};

export default HistorySidebar;
