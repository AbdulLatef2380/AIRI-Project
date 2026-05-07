import C from "../theme/colors.js";
import T from "../theme/typography.js";

/**
 * Full-screen overlay with an animated bottom sheet panel.
 * The backdrop click closes the sheet. The sheet itself slides up
 * via the global `sheetUp` keyframe defined in index.css.
 *
 * Props:
 *  - title    {string}   Optional heading rendered inside the sheet.
 *  - onClose  {Function} Called when backdrop or close action is triggered.
 *  - children {ReactNode}
 *  - compact  {boolean}  Reduces vertical padding for shorter menus.
 */
const BottomSheet = ({ title, onClose, children, compact = false }) => (
  <div
    style={{
      position: "absolute",
      inset: 0,
      zIndex: 50,
      display: "flex",
      flexDirection: "column",
      justifyContent: "flex-end",
    }}
  >
    {/* Backdrop */}
    <div
      onClick={onClose}
      style={{ flex: 1, background: "rgba(0,0,0,0.55)" }}
    />

    {/* Panel */}
    <div
      style={{
        background: C.surface,
        borderRadius: "20px 20px 0 0",
        padding: compact ? "16px 20px 30px" : "20px 20px 32px",
        maxHeight: "75%",
        overflowY: "auto",
        border: `1px solid ${C.border}`,
        animation: "sheetUp .25s ease",
      }}
    >
      {/* Drag handle */}
      <div
        style={{
          width: 36,
          height: 4,
          borderRadius: 2,
          background: C.borderB,
          margin: "0 auto 16px",
        }}
      />

      {title && (
        <div
          style={{
            fontSize: T.fontXl,
            fontWeight: 700,
            color: C.text,
            marginBottom: 16,
            textAlign: "right",
          }}
        >
          {title}
        </div>
      )}

      {children}
    </div>
  </div>
);

export default BottomSheet;
