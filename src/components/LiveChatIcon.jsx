import C from "../theme/colors.js";
import Icon from "./Icon.jsx";

/**
 * Circular pulsing icon used in the chat input bar.
 * The pulse animation is driven by the global `pulse` keyframe in index.css.
 */
const LiveChatIcon = ({ size = 22, active }) => (
  <div style={{ position: "relative", width: size, height: size }}>
    <div
      style={{
        width: size,
        height: size,
        borderRadius: "50%",
        background: `radial-gradient(circle, ${C.accent}33, ${C.accent}11)`,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        animation: active ? "pulse 1.5s ease-in-out infinite" : "none",
      }}
    >
      <Icon name="voice" size={size * 0.7} color={C.accent} />
    </div>
  </div>
);

export default LiveChatIcon;
