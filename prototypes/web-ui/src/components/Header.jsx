import React from "react";

const tabs = [
  { id: "overview", label: "Overview" },
  { id: "architecture", label: "Architecture" },
  { id: "status", label: "Build Status" },
];

export default function Header({ activeTab, setActiveTab }) {
  return (
    <header style={{
      background: "var(--surface)",
      borderBottom: "1px solid var(--border)",
      padding: "0 2rem",
      position: "sticky",
      top: 0,
      zIndex: 100,
      backdropFilter: "blur(12px)",
    }}>
      <div style={{
        maxWidth: 1100,
        margin: "0 auto",
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        height: 60,
      }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span style={{ fontSize: 22, fontWeight: 800, letterSpacing: "-0.5px" }}>
            <span style={{ color: "var(--accent2)" }}>AIRI</span>
          </span>
          <span style={{
            fontSize: 11,
            background: "var(--surface2)",
            border: "1px solid var(--border)",
            borderRadius: 4,
            padding: "1px 7px",
            color: "var(--muted)",
            letterSpacing: 1,
            fontWeight: 600,
          }}>ANDROID</span>
        </div>

        <nav style={{ display: "flex", gap: 4 }}>
          {tabs.map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              style={{
                background: activeTab === tab.id ? "var(--accent)" : "transparent",
                color: activeTab === tab.id ? "#fff" : "var(--muted)",
                border: "none",
                borderRadius: 6,
                padding: "6px 14px",
                cursor: "pointer",
                fontWeight: 500,
                fontSize: 13,
                transition: "all 0.15s",
              }}
            >
              {tab.label}
            </button>
          ))}
        </nav>
      </div>
    </header>
  );
}
