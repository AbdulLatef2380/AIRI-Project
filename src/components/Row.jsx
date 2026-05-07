import C from "../theme/colors.js";
import T from "../theme/typography.js";
import Icon from "./Icon.jsx";

/**
 * Generic list row used throughout settings and detail screens.
 *
 * Props:
 *  - icon    {ReactNode} Icon element to display.
 *  - iconBg  {string}   If provided wraps the icon in a coloured rounded square.
 *  - label   {string}   Primary label text.
 *  - sub     {string}   Optional secondary label (truncated with ellipsis).
 *  - right   {ReactNode} Optional right-side slot; defaults to a chevron arrow.
 *  - onClick {Function} Optional click handler; sets cursor to pointer.
 *  - danger  {boolean}  Renders label in danger colour.
 *  - badge   {string}   Optional accent badge rendered before the right slot.
 */
const Row = ({ icon, iconBg, label, sub, right, onClick, danger, badge }) => (
  <div
    onClick={onClick}
    style={{
      display: "flex",
      alignItems: "center",
      gap: 12,
      padding: "14px 0",
      borderBottom: `1px solid ${C.border}`,
      cursor: onClick ? "pointer" : "default",
      transition: "opacity .15s",
    }}
    onMouseEnter={e => (e.currentTarget.style.opacity = ".75")}
    onMouseLeave={e => (e.currentTarget.style.opacity = "1")}
  >
    {iconBg ? (
      <div
        style={{
          width: 40,
          height: 40,
          borderRadius: 10,
          background: iconBg,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          flexShrink: 0,
        }}
      >
        {icon}
      </div>
    ) : icon ? (
      <div style={{ flexShrink: 0 }}>{icon}</div>
    ) : null}

    <div style={{ flex: 1, minWidth: 0 }}>
      <div
        style={{
          fontSize: T.fontMd,
          color: danger ? C.danger : C.text,
          fontWeight: 500,
        }}
      >
        {label}
      </div>
      {sub && (
        <div
          style={{
            fontSize: T.fontSm,
            color: C.textB,
            marginTop: 2,
            whiteSpace: "nowrap",
            overflow: "hidden",
            textOverflow: "ellipsis",
          }}
        >
          {sub}
        </div>
      )}
    </div>

    {badge && (
      <div
        style={{
          fontSize: "10px",
          color: C.accent,
          background: `${C.accent}22`,
          borderRadius: 6,
          padding: "2px 7px",
        }}
      >
        {badge}
      </div>
    )}

    {right !== undefined ? (
      right
    ) : (
      <Icon
        name="arrowLeft"
        size={16}
        color={C.textC}
        style={{ transform: "rotate(180deg)" }}
      />
    )}
  </div>
);

export default Row;
