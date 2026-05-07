import { Component } from "react";
import C from "../theme/colors.js";
import T from "../theme/typography.js";

/**
 * Global and screen-level error boundary.
 * Catches render-phase and lifecycle errors and renders a dark-themed
 * fallback that matches the AIRI design system exactly.
 * Never white-screens the app.
 */
export class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null, info: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, info) {
    this.setState({ info });
    console.error("[AIRI ErrorBoundary]", error, info?.componentStack);
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null, info: null });
  };

  render() {
    const { hasError, error } = this.state;
    const { children, fallback, inline = false } = this.props;

    if (!hasError) return children;

    if (fallback) return fallback;

    const containerStyle = inline
      ? {
          display: "flex", flexDirection: "column", alignItems: "center",
          justifyContent: "center", gap: 12, padding: 24,
          background: C.surface, borderRadius: 14,
          border: `1px solid ${C.border}`,
        }
      : {
          display: "flex", flexDirection: "column", alignItems: "center",
          justifyContent: "center", gap: 16, flex: 1,
          background: C.bg, padding: 24,
        };

    return (
      <div style={containerStyle}>
        <div style={{
          width: 48, height: 48, borderRadius: "50%",
          background: `${C.danger}22`,
          display: "flex", alignItems: "center", justifyContent: "center",
        }}>
          <svg width={24} height={24} viewBox="0 0 24 24" fill="none"
            stroke={C.danger} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="8" x2="12" y2="12"/>
            <line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
        </div>

        <div style={{ textAlign: "center" }}>
          <div style={{ fontSize: T.fontMd, color: C.text, fontWeight: 600, marginBottom: 6 }}>
            حدث خطأ غير متوقع
          </div>
          {error && (
            <div style={{
              fontSize: T.fontSm, color: C.textC, fontFamily: "monospace",
              maxWidth: 280, wordBreak: "break-word",
            }}>
              {error.message}
            </div>
          )}
        </div>

        <div
          onClick={this.handleReset}
          style={{
            background: C.accent, borderRadius: 10, padding: "10px 20px",
            cursor: "pointer", fontSize: T.fontSm, color: "white", fontWeight: 600,
          }}
        >
          إعادة المحاولة
        </div>
      </div>
    );
  }
}

/** Convenience wrapper for screen-level isolation */
export function ScreenBoundary({ children }) {
  return (
    <ErrorBoundary inline>
      {children}
    </ErrorBoundary>
  );
}

export default ErrorBoundary;
