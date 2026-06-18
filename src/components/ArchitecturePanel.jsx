import React, { useState } from "react";

const layers = [
  { name: "UI Layer", color: "#7c6af7", items: ["Jetpack Compose", "Material 3", "ChatScreen", "SettingsScreen", "ConnectorsScreen", "SkillManagerScreen"], desc: "Cosmic design system built on Material 3. All screens are Compose-first with ViewModel state hoisting." },
  { name: "Core Layer", color: "#a78bfa", items: ["UnifiedCognitiveLoop", "ServiceLocator", "AIRIApplication", "MainActivity"], desc: "Orchestration hub. UnifiedCognitiveLoop drives the full Input → Planning → Execution → Learning pipeline." },
  { name: "Agent Layer", color: "#06b6d4", items: ["PlanGenerator", "CommandRouter", "DecisionEngine", "ActionPlan (JSON)"], desc: "Planning and routing. PlanGenerator produces structured JSON ActionPlans; CommandRouter dispatches to the correct executor." },
  { name: "World Layer", color: "#22c55e", items: ["WorldStateManager", "BatteryMonitor", "NetworkMonitor", "ActiveAppTracker"], desc: "Device environment awareness. Feeds real-time context into every planning cycle." },
  { name: "Memory Layer", color: "#f97316", items: ["MemoryManager", "MemoryEvolutionEngine", "RagRetriever", "ConversationSummarizer", "CloudSyncCoordinator"], desc: "Persistent episodic memory with cosine similarity RAG, temporal decay ranking, and Firestore cloud sync." },
  { name: "Accessibility Layer", color: "#eab308", items: ["AiriAccessibilityService", "UITreeScanner", "ActionExecutor"], desc: "Android Accessibility API integration. Reads the full UI tree and performs clicks, swipes, and text input autonomously." },
  { name: "AI Layer", color: "#ec4899", items: ["LlamaManager (JNI)", "PromptService", "DynamicPromptEngine", "EmbeddingService"], desc: "llama.cpp native inference via JNI. Dynamic prompt assembly with RAG injection, skill blocks, and token budget enforcement." },
  { name: "Tools Layer", color: "#8b5cf6", items: ["ToolRegistry", "SkillRegistry", "MediaLibrary", "ArtifactManager"], desc: "Extensible tool and skill system with versioning, dependency validation, and a unified media repository." },
  { name: "App Layer", color: "#ef4444", items: ["Firebase Analytics", "Crashlytics (NDK)", "Firebase Auth", "EncryptedSharedPreferences", "Play Integrity"], desc: "Cross-cutting concerns: crash reporting with NDK symbolication, analytics, biometric auth, and secure storage." },
];

export default function ArchitecturePanel() {
  const [selected, setSelected] = useState(0);

  return (
    <section style={{ padding: "60px 2rem", maxWidth: 1100, margin: "0 auto" }}>
      <h2 style={{ fontSize: "1.5rem", fontWeight: 800, marginBottom: 8, letterSpacing: "-0.5px" }}>
        9-Layer Clean Architecture
      </h2>
      <p style={{ color: "var(--muted)", marginBottom: 36, fontSize: 14 }}>
        Each layer has a single responsibility and communicates via well-defined interfaces.
        Click a layer to explore its components.
      </p>

      <div style={{ display: "grid", gridTemplateColumns: "280px 1fr", gap: 20 }}>
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          {layers.map((layer, i) => (
            <button
              key={layer.name}
              onClick={() => setSelected(i)}
              style={{
                background: selected === i ? `${layer.color}22` : "var(--surface)",
                border: `1px solid ${selected === i ? layer.color : "var(--border)"}`,
                borderRadius: 8,
                padding: "10px 14px",
                cursor: "pointer",
                textAlign: "left",
                color: selected === i ? layer.color : "var(--text)",
                fontWeight: selected === i ? 700 : 500,
                fontSize: 13,
                transition: "all 0.15s",
                display: "flex",
                alignItems: "center",
                gap: 8,
              }}
            >
              <span style={{
                width: 8, height: 8, borderRadius: "50%",
                background: layer.color, flexShrink: 0,
              }} />
              {layer.name}
            </button>
          ))}
        </div>

        <div style={{
          background: "var(--surface)",
          border: `1px solid ${layers[selected].color}66`,
          borderRadius: 12,
          padding: 28,
        }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 16 }}>
            <span style={{
              width: 12, height: 12, borderRadius: "50%",
              background: layers[selected].color, flexShrink: 0,
            }} />
            <h3 style={{ fontWeight: 800, fontSize: 17, color: layers[selected].color }}>
              {layers[selected].name}
            </h3>
          </div>

          <p style={{ color: "var(--muted)", fontSize: 14, lineHeight: 1.7, marginBottom: 20 }}>
            {layers[selected].desc}
          </p>

          <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
            {layers[selected].items.map(item => (
              <span key={item} style={{
                background: `${layers[selected].color}18`,
                border: `1px solid ${layers[selected].color}44`,
                color: layers[selected].color,
                borderRadius: 6,
                padding: "4px 12px",
                fontSize: 12,
                fontWeight: 600,
                fontFamily: "monospace",
              }}>{item}</span>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
