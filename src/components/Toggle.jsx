import C from "../theme/colors.js";

const Toggle = ({ on, onChange }) => (
  <div
    onClick={() => onChange(!on)}
    style={{
      width: 44,
      height: 26,
      borderRadius: 13,
      background: on ? C.accent : C.surfaceC,
      position: "relative",
      cursor: "pointer",
      transition: "background .2s",
      flexShrink: 0,
    }}
  >
    <div
      style={{
        position: "absolute",
        top: 3,
        left: on ? 21 : 3,
        width: 20,
        height: 20,
        borderRadius: "50%",
        background: "white",
        transition: "left .2s",
        boxShadow: "0 1px 4px rgba(0,0,0,0.4)",
      }}
    />
  </div>
);

export default Toggle;
