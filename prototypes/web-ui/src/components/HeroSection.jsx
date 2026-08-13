import React from "react";

export default function HeroSection() {
  return (
    <section style={{
      background: "linear-gradient(135deg, #0a0a12 0%, #12101f 50%, #0a0a12 100%)",
      padding: "80px 2rem 60px",
      textAlign: "center",
      borderBottom: "1px solid var(--border)",
      position: "relative",
      overflow: "hidden",
    }}>
      <div style={{
        position: "absolute", inset: 0, opacity: 0.03,
        backgroundImage: "radial-gradient(circle at 1px 1px, var(--accent2) 1px, transparent 0)",
        backgroundSize: "40px 40px",
      }} />

      <div style={{ position: "relative", maxWidth: 800, margin: "0 auto" }}>
        <div style={{
          display: "inline-flex",
          alignItems: "center",
          gap: 8,
          background: "rgba(124,106,247,0.12)",
          border: "1px solid rgba(124,106,247,0.3)",
          borderRadius: 20,
          padding: "5px 14px",
          marginBottom: 24,
          fontSize: 12,
          color: "var(--accent2)",
          fontWeight: 600,
          letterSpacing: 0.5,
        }}>
          <span style={{ width: 6, height: 6, borderRadius: "50%", background: "var(--green)", display: "inline-block" }} />
          PRIVACY-FIRST · ON-DEVICE AI · KOTLIN + C++
        </div>

        <h1 style={{
          fontSize: "clamp(2.2rem, 6vw, 3.5rem)",
          fontWeight: 900,
          letterSpacing: "-1.5px",
          lineHeight: 1.1,
          marginBottom: 20,
        }}>
          Android Artificial Intelligence<br />
          <span style={{
            background: "linear-gradient(135deg, var(--accent), var(--accent2))",
            WebkitBackgroundClip: "text",
            WebkitTextFillColor: "transparent",
          }}>Runtime Interface</span>
        </h1>

        <p style={{
          color: "var(--muted)",
          fontSize: "1.1rem",
          maxWidth: 580,
          margin: "0 auto 36px",
          lineHeight: 1.7,
        }}>
          Autonomous AI assistant for Android. Processes commands on-device using{" "}
          <strong style={{ color: "var(--text)" }}>llama.cpp</strong> via JNI,
          controls your device through Accessibility Services, and understands
          your environment — all without sending data to the cloud.
        </p>

        <div style={{ display: "flex", gap: 12, justifyContent: "center", flexWrap: "wrap" }}>
          {[
            { label: "Min SDK", value: "Android 8.0 (API 26)" },
            { label: "Target SDK", value: "API 34" },
            { label: "Language", value: "Kotlin 1.9.22 + C++17" },
            { label: "Architecture", value: "9-Layer Clean Arch" },
          ].map(stat => (
            <div key={stat.label} style={{
              background: "var(--surface2)",
              border: "1px solid var(--border)",
              borderRadius: 10,
              padding: "10px 18px",
              textAlign: "center",
            }}>
              <div style={{ fontSize: 11, color: "var(--muted)", marginBottom: 2, textTransform: "uppercase", letterSpacing: 0.5 }}>{stat.label}</div>
              <div style={{ fontWeight: 700, fontSize: 13, color: "var(--text)" }}>{stat.value}</div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
