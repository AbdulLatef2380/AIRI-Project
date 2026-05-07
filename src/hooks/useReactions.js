import { useState, useCallback } from "react";
import { store } from "../core/persistence/store.js";

/**
 * useReactions
 *
 * Per-conversation message reactions (thumbs up / thumbs down).
 * Persisted to localStorage under a per-conversation key.
 *
 * Returns:
 *   reactions   — { [msgId]: "up" | "down" }
 *   react(msgId, type)  — toggle reaction (second call with same type removes it)
 *   getReaction(msgId)  — "up" | "down" | null
 */
export function useReactions(convId) {
  const storeKey = `reactions_${convId ?? "default"}`;

  const [reactions, setReactions] = useState(() => {
    return store.get(storeKey) ?? {};
  });

  /* Re-sync if convId changes */
  const syncConvId = useCallback((id) => {
    const k = `reactions_${id ?? "default"}`;
    return store.get(k) ?? {};
  }, []);

  const react = useCallback((msgId, type) => {
    setReactions(prev => {
      const current = prev[msgId];
      /* Toggle: clicking same type removes it */
      const updated = { ...prev };
      if (current === type) {
        delete updated[msgId];
      } else {
        updated[msgId] = type;
      }
      store.set(storeKey, updated);
      return updated;
    });
  }, [storeKey]);

  const getReaction = useCallback((msgId) => {
    return reactions[msgId] ?? null;
  }, [reactions]);

  return { reactions, react, getReaction };
}
