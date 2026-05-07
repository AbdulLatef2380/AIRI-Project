import { useState, useCallback, useRef, useEffect } from "react";
import { chatEngine } from "../core/chat/ChatEngine.js";
import { useApp } from "../context/useApp.js";
import { SkillRegistry, buildSystemPromptFromSkills } from "../core/skills/SkillRegistry.js";

const MSG_ID = () => `msg_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;

/**
 * useChat
 *
 * Manages conversation state and wires the UI to the ChatEngine.
 * Provides: messages, sendMessage, cancelMessage, isStreaming, streamingId
 *
 * All async operations are guarded against unmounted-component updates
 * via the `mounted` ref.
 */
export function useChat(conversationId = "default") {
  const { activeModel, apiKeys, localEndpoint, upsertConversation, conversations } = useApp();

  /* ── Restore existing conversation from context ─────────────── */
  const existing     = conversations.find(c => c.id === conversationId);
  const [messages, setMessages] = useState(existing?.messages ?? []);
  const [isStreaming, setIsStreaming]   = useState(false);
  const [streamingId, setStreamingId]  = useState(null);
  const [error, setError]              = useState(null);

  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  /* ── Persist conversation whenever messages change ─────────── */
  useEffect(() => {
    if (messages.length === 0) return;
    upsertConversation({
      id:         conversationId,
      messages,
      updatedAt:  Date.now(),
      model:      activeModel,
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages]);

  /* ── Build provider config from context ─────────────────────── */
  const getConfig = useCallback(() => ({
    apiKey:        apiKeys?.openai    ?? "",
    anthropicKey:  apiKeys?.anthropic ?? "",
    localEndpoint: localEndpoint ?? "http://localhost:11434",
  }), [apiKeys, localEndpoint]);

  /* ── Send a message ─────────────────────────────────────────── */
  const sendMessage = useCallback(async (text) => {
    if (!text.trim() || isStreaming) return;
    setError(null);

    const userMsg = { id: MSG_ID(), role: "user", content: text.trim() };
    const asstId  = MSG_ID();
    const asstMsg = { id: asstId, role: "assistant", content: "", streaming: true };

    setMessages(prev => [...prev, userMsg, asstMsg]);
    setIsStreaming(true);
    setStreamingId(asstId);

    /* Build history for API (no streaming placeholders) */
    const history = [...messages, userMsg].map(m => ({
      role:    m.role,
      content: m.content,
    }));

    /* Optionally inject enabled skills into system prompt */
    const skillIds      = SkillRegistry.getEnabledIds();
    const skillContext  = buildSystemPromptFromSkills(skillIds);
    const options       = skillContext ? { systemExtra: skillContext } : {};

    let accumulated = "";

    for await (const event of chatEngine.send(history, activeModel, getConfig(), options)) {
      if (!mounted.current) break;

      if (event.token) {
        accumulated += event.token;
        setMessages(prev =>
          prev.map(m =>
            m.id === asstId
              ? { ...m, content: accumulated, streaming: true }
              : m
          )
        );
      }

      if (event.done) {
        setMessages(prev =>
          prev.map(m =>
            m.id === asstId
              ? { ...m, streaming: false, usage: event.usage }
              : m
          )
        );
        break;
      }

      if (event.aborted) {
        setMessages(prev =>
          prev.map(m =>
            m.id === asstId
              ? { ...m, content: accumulated || "…تم الإلغاء", streaming: false, cancelled: true }
              : m
          )
        );
        break;
      }

      if (event.error) {
        setMessages(prev =>
          prev.map(m =>
            m.id === asstId
              ? { ...m, content: `⚠️ ${event.error}`, streaming: false, isError: true }
              : m
          )
        );
        setError(event.error);
        break;
      }

      if (event.retrying) {
        setMessages(prev =>
          prev.map(m =>
            m.id === asstId
              ? { ...m, content: `…إعادة المحاولة (${event.retrying}/${event.maxRetries})`, streaming: true }
              : m
          )
        );
      }
    }

    if (mounted.current) {
      setIsStreaming(false);
      setStreamingId(null);
    }
  }, [messages, isStreaming, activeModel, getConfig]);

  /* ── Cancel current request ─────────────────────────────────── */
  const cancelMessage = useCallback(() => {
    chatEngine.abort();
  }, []);

  /* ── Clear conversation ─────────────────────────────────────── */
  const clearMessages = useCallback(() => {
    setMessages([]);
    setError(null);
  }, []);

  return { messages, sendMessage, cancelMessage, clearMessages, isStreaming, streamingId, error };
}
