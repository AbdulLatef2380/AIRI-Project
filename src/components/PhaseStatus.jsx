import React from "react";

const phase1 = [
  { check: "ActivityCategory enum (all 12 values)", result: "PASS" },
  { check: "ActivitySeverity (INFO / WARN / ERROR)", result: "PASS" },
  { check: "DownloadResult.Success bug → .Ok", result: "PASS" },
  { check: "CONTEXT_RESET in ActivityCategory enum", result: "PASS" },
  { check: "Coroutine scope safety (SupervisorJob)", result: "PASS" },
  { check: "ConversationSummarizer wired", result: "PASS" },
  { check: "Gradle build config (compileSdk=34, minSdk=26)", result: "PASS" },
  { check: "Firebase / Room / Vosk dependencies", result: "PASS" },
  { check: "No duplicate symbols across 485 files", result: "PASS" },
  { check: "RagRetriever in ServiceLocator", result: "PASS" },
];

const phase2 = [
  { feature: "Long-term Memory (RAG)", status: "COMPLETE", score: 100, detail: "Room DB + cosine RAG + temporal decay + cloud sync" },
  { feature: "Cloud Memory Sync", status: "COMPLETE", score: 100, detail: "pushMemories() / pullMemories() with last-write-wins conflict resolution" },
  { feature: "Media Library", status: "COMPLETE", score: 100, detail: "MediaLibrary.kt — images, documents, generated artifacts, full-text search" },
  { feature: "Skill Registry", status: "COMPLETE", score: 100, detail: "Versioning, semver comparison, dependency validation, downgrade protection" },
  { feature: "Dynamic Prompt Engine", status: "COMPLETE", score: 96, detail: "10-slot assembly; 4pts reserved for GGUF embedding model availability" },
];

const modifiedFiles = [
  { file: "media/MediaLibrary.kt", action: "NEW", lines: 388 },
  { file: "ai/prompt/DynamicPromptEngine.kt", action: "NEW", lines: 263 },
  { file: "domain/prompt/PromptService.kt", action: "MODIFIED", lines: "+70" },
  { file: "ai/skills/SkillRegistry.kt", action: "MODIFIED", lines: "+100" },
  { file: "sync/CloudSyncCoordinator.kt", action: "MODIFIED", lines: "+120" },
  { file: "core/ServiceLocator.kt", action: "MODIFIED", lines: "+10" },
  { file: "ui/viewmodel/ChatViewModel.kt", action: "MODIFIED", lines: "+12" },
];

function Badge({ label, color }) {
  const colors = {
    PASS: { bg: "#22c55e22", border: "#22c55e44", text: "#22c55e" },
    FAIL: { bg: "#ef444422", border: "#ef444444", text: "#ef4444" },
    COMPLETE: { bg: "#7c6af722", border: "#7c6af744", text: "#a78bfa" },
    NEW: { bg: "#22c55e22", border: "#22c55e44", text: "#22c55e" },
    MODIFIED: { bg: "#eab30822", border: "#eab30844", text: "#eab308" },
  };
  const c = colors[label] || colors.PASS;
  return (
    <span style={{
      background: c.bg, border: `1px solid ${c.border}`, color: c.text,
      borderRadius: 5, padding: "2px 9px", fontSize: 11, fontWeight: 700, letterSpacing: 0.5,
    }}>{label}</span>
  );
}

export default function PhaseStatus() {
  return (
    <section style={{ padding: "60px 2rem", maxWidth: 1100, margin: "0 auto" }}>
      <h2 style={{ fontSize: "1.5rem", fontWeight: 800, marginBottom: 32, letterSpacing: "-0.5px" }}>
        Build & Verification Status
      </h2>

      <h3 style={{ fontWeight: 700, fontSize: 14, color: "var(--muted)", textTransform: "uppercase", letterSpacing: 1, marginBottom: 14 }}>
        Phase 1 — Build Verification
      </h3>
      <div style={{
        background: "var(--surface)",
        border: "1px solid var(--border)",
        borderRadius: 12,
        overflow: "hidden",
        marginBottom: 36,
      }}>
        {phase1.map((item, i) => (
          <div key={i} style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: "12px 18px",
            borderBottom: i < phase1.length - 1 ? "1px solid var(--border)" : "none",
          }}>
            <span style={{ fontSize: 13, color: "var(--text)" }}>{item.check}</span>
            <Badge label={item.result} />
          </div>
        ))}
        <div style={{
          padding: "14px 18px",
          background: "#22c55e11",
          borderTop: "1px solid #22c55e33",
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}>
          <span style={{ fontWeight: 700, fontSize: 13, color: "#22c55e" }}>Phase 1 Verdict: 100% CLEAN</span>
          <Badge label="PASS" />
        </div>
      </div>

      <h3 style={{ fontWeight: 700, fontSize: 14, color: "var(--muted)", textTransform: "uppercase", letterSpacing: 1, marginBottom: 14 }}>
        Phase 2 — Feature Implementation
      </h3>
      <div style={{
        display: "grid",
        gridTemplateColumns: "repeat(auto-fill, minmax(300px, 1fr))",
        gap: 12,
        marginBottom: 36,
      }}>
        {phase2.map(f => (
          <div key={f.feature} style={{
            background: "var(--surface)",
            border: "1px solid var(--border)",
            borderRadius: 10,
            padding: 18,
          }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
              <span style={{ fontWeight: 700, fontSize: 13 }}>{f.feature}</span>
              <Badge label={f.status} />
            </div>
            <div style={{ marginBottom: 10 }}>
              <div style={{
                height: 4, background: "var(--border)", borderRadius: 2, overflow: "hidden",
              }}>
                <div style={{
                  width: `${f.score}%`, height: "100%",
                  background: f.score === 100 ? "var(--green)" : "var(--accent2)",
                  borderRadius: 2,
                }} />
              </div>
              <span style={{ fontSize: 11, color: "var(--muted)", marginTop: 4, display: "block" }}>
                Score: {f.score}/100
              </span>
            </div>
            <p style={{ fontSize: 12, color: "var(--muted)", lineHeight: 1.6 }}>{f.detail}</p>
          </div>
        ))}
      </div>

      <h3 style={{ fontWeight: 700, fontSize: 14, color: "var(--muted)", textTransform: "uppercase", letterSpacing: 1, marginBottom: 14 }}>
        Modified & Created Files
      </h3>
      <div style={{
        background: "var(--surface)",
        border: "1px solid var(--border)",
        borderRadius: 12,
        overflow: "hidden",
      }}>
        {modifiedFiles.map((f, i) => (
          <div key={i} style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: "11px 18px",
            borderBottom: i < modifiedFiles.length - 1 ? "1px solid var(--border)" : "none",
          }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <Badge label={f.action} />
              <code style={{ fontSize: 12, color: "var(--text)", fontFamily: "monospace" }}>
                app/src/main/java/com/airi/assistant/{f.file}
              </code>
            </div>
            <span style={{ fontSize: 12, color: "var(--muted)", whiteSpace: "nowrap", marginLeft: 16 }}>
              {f.lines} lines
            </span>
          </div>
        ))}
      </div>
    </section>
  );
}
