import C from "../theme/colors.js";

/** Heights of the 7 waveform bars (left → right, symmetric). */
const BAR_HEIGHTS = [3, 6, 9, 12, 9, 6, 3];

/**
 * Animated equaliser waveform.
 * When `active` is true each bar oscillates via the global `wave` keyframe
 * (defined in index.css). Bars animate with staggered durations for a
 * natural look.
 */
const Waveform = ({ active, size = 22, color = C.accent }) => (
  <svg
    width={size * 1.6}
    height={size}
    viewBox="0 0 56 24"
    style={{ display: "block" }}
  >
    {BAR_HEIGHTS.map((h, i) => (
      <rect
        key={i}
        x={i * 8}
        y={(24 - h) / 2}
        width="5"
        height={h}
        rx="2.5"
        fill={color}
        style={
          active
            ? {
                animation: `wave ${0.6 + i * 0.1}s ease-in-out infinite alternate`,
                transformOrigin: "center",
              }
            : {}
        }
      />
    ))}
  </svg>
);

export default Waveform;
