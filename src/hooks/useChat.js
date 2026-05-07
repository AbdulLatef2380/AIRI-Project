import { useState, useCallback, useRef, useEffect } from "react";
import { chatEngine } from "../core/chat/ChatEngine.js";
import { useApp } from "../context/useApp.js";
import { SkillRegistry, buildSystemPromptFromSkills } from "../core/skills/SkillRegistry.js";

const MSG_ID = () => `msg_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;

/**
 * useChat
 *
 * Manages conversation state and wires the UI to the ChatEngine.
 *
 * Bug fixes:
 *  - Stale closure: messages stored in ref so sendMessage never reads stale state
 *  - Conversation switching: messages reset when conversationId changes
 *  - Abort on unmount: prevents state updates after unmount
 *  - Engine recovery: abort called on unmount to unblock engine
 */
export function useChat(conversationId = "default") {
  const { activeModel, apiKeys, localEndpoint, upsertConversation, conversations } = useApp();

  const existing = conversations.find(c => c.id === conversationId);

  const [messages, setMessages]       = useState(existing?.messages ?? []);
  const [isStreaming, setIsStreaming]  = useState(false);
  const [streamingId, setStreamingId] = useState(null);
  const [error, setError]             = useState(null);

  /* Always-current ref — avoids stale closure in sendMessage */
  const messagesRef = useRef(messages);
  useEffect(() => { messagesRef.current = messages; }, [messages]);

  /* Unmount guard */
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      chatEngine.abort(); /* release engine on unmount */
    };
  }, []);

  /* ── Reset when conversation changes (history sidebar navigation) */
  useEffect(() => {
    const conv = conversations.find(c => c.id === conversationId);
    setMessages(conv?.messages ?? []);
    setError(null);
    setIsStreaming(false);
    setStreamingId(null);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conversationId]);

  /* ── Persist conversation whenever messages change ── */
  useEffect(() => {
    if (messages.length === 0) return;
    upsertConversation({
      id:        conversationId,
      messages,
      updatedAt: Date.now(),
      model:     activeModel,
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages]);

  /* ── Provider config — always fresh via ref ── */
  const configRef = useRef({});
  configRef.current = {
    apiKey:        apiKeys?.openai    ?? "",
    anthropicKey:  apiKeys?.anthropic ?? "",
    localEndpoint: localEndpoint ?? "http://localhost:11434",
  };

  /* ── Send a message ── */
  const sendMessage = useCallback(async (text, opts = {}) => {
    if (!text?.trim()) return;
    if (chatEngine.isBusy) {
      chatEngine.abort();
      await new Promise(r => setTimeout(r, 100));
    }

    setError(null);

    const userMsg = {
      id: MSG_ID(), role: "user", content: text.trim(),
      voiceInput: opts.voiceInput ?? false,
    };
    const asstId  = MSG_ID();
    const asstMsg = { id: asstId, role: "assistant", content: "", streaming: true };

    setMessages(prev => [...prev, userMsg, asstMsg]);
    setIsStreaming(true);
    setStreamingId(asstId);

    /* Build history from ref — always latest, no stale closure */
    const history = [...messagesRef.current, userMsg].map(m => ({
      role:    m.role,
      content: m.content,
    }));

    /* Inject enabled skills */
    const skillIds     = SkillRegistry.getEnabledIds();
    const skillContext = buildSystemPromptFromSkills(skillIds);
    const options      = skillContext ? { systemExtra: skillContext } : {};

    let accumulated = "";
    let finalContent = "";

    try {
      for await (const event of chatEngine.send(history, activeModel, configRef.current, options)) {
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
          finalContent = accumulated;
          setMessages(prev =>
            prev.map(m =>
              m.id === asstId
                ? { ...m, content: accumulated, streaming: false, usage: event.usage }
                : m
            )
          );
          break;
        }

        if (event.aborted) {
          finalContent = accumulated;
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
          if (mounted.current) setError(event.error);
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
    } catch (err) {
      if (mounted.current) {
        setMessages(prev =>
          prev.map(m =>
            m.id === asstId
              ? { ...m, content: `⚠️ ${err.message ?? "خطأ غير متوقع"}`, streaming: false, isError: true }
              : m
          )
        );
        setError(err.message ?? "خطأ غير متوقع");
      }
    }

    if (mounted.current) {
      setIsStreaming(false);
      setStreamingId(null);
    }

    /* Return final content so callers can trigger TTS etc. */
    return finalContent;
  // intentionally no deps — all state accessed via refs
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeModel]);

  /* ── Cancel ── */
  const cancelMessage = useCallback(() => {
    chatEngine.abort();
  }, []);

  /* ── Clear ── */
  const clearMessages = useCallback(() => {
    setMessages([]);
    setError(null);
  }, []);

  return { messages, sendMessage, cancelMessage, clearMessages, isStreaming, streamingId, error };
}
