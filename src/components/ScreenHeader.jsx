import C from "../theme/colors.js";
import T from "../theme/typography.js";
import Icon from "./Icon.jsx";

const ScreenHeader = ({ title, onBack, right }) => (
  <div
    style={{
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between",
      padding: "16px 0 12px",
      marginBottom: 4,
    }}
  >
    {onBack ? (
      <div
        onClick={onBack}
        style={{ cursor: "pointer", padding: "4px 8px 4px 0" }}
      >
        <Icon name="arrowLeft" size={22} color={C.text} />
      </div>
    ) : (
      <div style={{ width: 30 }} />
    )}

    <div style={{ fontSize: T.fontXl, fontWeight: 700, color: C.text }}>
      {title}
    </div>

    {right !== undefined ? right : <div style={{ width: 30 }} />}
  </div>
);

export default ScreenHeader;
