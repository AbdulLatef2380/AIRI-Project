import ICONS from "../constants/icons.jsx";
import C from "../theme/colors.js";

/**
 * ICONS is imported from module scope — it is never recreated on re-render.
 * Renders a 24×24 SVG with the requested icon name.
 */
const Icon = ({ name, size = 20, color = C.text, style = {} }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke={color}
    strokeWidth="1.8"
    strokeLinecap="round"
    strokeLinejoin="round"
    style={{ flexShrink: 0, ...style }}
  >
    {ICONS[name]}
  </svg>
);

export default Icon;
