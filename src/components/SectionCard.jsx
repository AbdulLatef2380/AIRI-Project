import C from "../theme/colors.js";
import R from "../theme/radius.js";

const SectionCard = ({ children, style = {} }) => (
  <div
    style={{
      background: C.surface,
      borderRadius: R.radius,
      padding: "0 16px",
      marginBottom: 12,
      border: `1px solid ${C.border}`,
      ...style,
    }}
  >
    {children}
  </div>
);

export default SectionCard;
