import React from "react";

const features = [
  {
    icon: "🧠",
    title: "On-Device LLM",
    desc: "llama.cpp integrated via JNI for fully local inference. No API keys, no cloud calls, no data leakage.",
    tag: "CORE",
    tagColor: "var(--accent)",
  },
  {
    icon: "🎙️",
    title: "Voice Interface",
    desc: "Offline speech recognition via Vosk. Wake-word detection via Picovoice Porcupine and TensorFlow Lite OpenWakeWord.",
    tag: "VOICE",
    tagColor: "var(--cyan)",
  },
  {
    icon: "♿",
    title: "Accessibility Control",
    desc: "AiriAccessibilityService scans UI trees to perform OpenApp, Click, Type, and Swipe actions autonomously.",
    tag: "AGENT",
    tagColor: "var(--yellow)",
  },
  {
    icon: "🌍",
    title: "World State Manager",
    desc: "Monitors battery, network, active app, and device context. Decisions adapt to real-time environment.",
    tag: "CONTEXT",
    tagColor: "var(--green)",
  },
  {
    icon: "💾",
    title: "Long-Term Memory",
    desc: "Room DB with 200-row sliding window, cosine similarity RAG retrieval, temporal decay ranking, and cloud sync via Firestore.",
    tag: "MEMORY",
    tagColor: "#f97316",
  },
  {
    icon: "⚙️",
    title: "Skill Registry",
    desc: "Install, version, and validate skills with semver comparison, dependency graphs, and downgrade protection.",
    tag: "SKILLS",
    tagColor: "#ec4899",
  },
  {
    icon: "🔐",
    title: "Security First",
    desc: "Biometric auth, EncryptedSharedPreferences, Play Integrity API, and ProGuard rules. Local-only by default.",
    tag: "SECURITY",
    tagColor: "var(--red)",
  },
  {
    icon: "📚",
    title: "Media Library",
    desc: "Unified repository for images, documents, and AI-generated artifacts. Full-text search, MIME detection, and session tagging.",
    tag: "MEDIA",
    tagColor: "#8b5cf6",
  },
  {
    icon: "✨",
    title: "Dynamic Prompt Engine",
    desc: "10-slot assembly pipeline injecting RAG context, memory summaries, skill awareness, and tool descriptions per request.",
    tag: "AI",
    tagColor: "var(--accent2)",
  },
];

export default function FeatureGrid() {
  return (
    <section style={{ padding: "60px 2rem", maxWidth: 1100, margin: "0 auto" }}>
      <h2 style={{ fontSize: "1.5rem", fontWeight: 800, marginBottom: 8, letterSpacing: "-0.5px" }}>
        Core Capabilities
      </h2>
      <p style={{ color: "var(--muted)", marginBottom: 36, fontSize: 14 }}>
        All features run entirely on-device — no internet required for core operation.
      </p>

      <div style={{
        display: "grid",
        gridTemplateColumns: "repeat(auto-fill, minmax(300px, 1fr))",
        gap: 16,
      }}>
        {features.map(f => (
          <div key={f.title} style={{
            background: "var(--surface)",
            border: "1px solid var(--border)",
            borderRadius: 12,
            padding: "20px 22px",
            transition: "border-color 0.15s",
            cursor: "default",
          }}
            onMouseEnter={e => e.currentTarget.style.borderColor = "var(--accent)"}
            onMouseLeave={e => e.currentTarget.style.borderColor = "var(--border)"}
          >
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 10 }}>
              <span style={{ fontSize: 26 }}>{f.icon}</span>
              <span style={{
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: 1,
                background: `${f.tagColor}22`,
                color: f.tagColor,
                border: `1px solid ${f.tagColor}44`,
                borderRadius: 4,
                padding: "2px 8px",
              }}>{f.tag}</span>
            </div>
            <h3 style={{ fontWeight: 700, fontSize: 15, marginBottom: 6 }}>{f.title}</h3>
            <p style={{ color: "var(--muted)", fontSize: 13, lineHeight: 1.65 }}>{f.desc}</p>
          </div>
        ))}
      </div>
    </section>
  );
}
