import React from "react";

export default function Footer() {
  return (
    <footer style={{
      background: "var(--surface)",
      borderTop: "1px solid var(--border)",
      padding: "20px 2rem",
      textAlign: "center",
      color: "var(--muted)",
      fontSize: 12,
    }}>
      <div style={{ maxWidth: 1100, margin: "0 auto", display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 8 }}>
        <span>
          <strong style={{ color: "var(--accent2)" }}>AIRI</strong> — Android Artificial Intelligence Runtime Interface
        </span>
        <span style={{ display: "flex", gap: 16 }}>
          <span>Kotlin 1.9.22</span>
          <span>·</span>
          <span>compileSdk 34</span>
          <span>·</span>
          <span>NDK 25.2.9519653</span>
          <span>·</span>
          <span>485 source files</span>
        </span>
      </div>
    </footer>
  );
}
