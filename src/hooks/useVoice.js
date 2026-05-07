import { useState, useRef, useEffect, useCallback } from "react";

/**
 * useVoice
 *
 * Web Speech API wrapper with full lifecycle management.
 * Supports Arabic (ar-SA) and English fallback.
 * Yields interim tokens for live display and final transcript on commit.
 *
 * Callbacks:
 *   onResult(text)  — called when a final transcript is ready
 *   onInterim(text) — called with live partial transcript
 *   onError(msg)    — called with a human-readable Arabic error string
 *
 * Returns:
 *   { isListening, isSupported, toggle, stop }
 */

/* Detect API availability once at module load */
const SR =
  typeof window !== "undefined"
    ? window.SpeechRecognition || window.webkitSpeechRecognition || null
    : null;

const ERROR_MESSAGES = {
  "not-allowed":   "تم رفض إذن الميكروفون. يرجى السماح بالوصول في إعدادات المتصفح.",
  "no-speech":     "لم يُكتشف أي كلام. حاول مجددًا.",
  "network":       "خطأ في الشبكة أثناء التعرف على الكلام.",
  "audio-capture": "لا يمكن الوصول إلى الميكروفون.",
  "aborted":       "",
};

export function useVoice({ onResult, onInterim, onError } = {}) {
  const [isListening, setIsListening] = useState(false);
  const recRef   = useRef(null);
  const mounted  = useRef(true);
  const cbRef    = useRef({ onResult, onInterim, onError });

  /* Keep callbacks fresh without recreating the recognition instance */
  useEffect(() => {
    cbRef.current = { onResult, onInterim, onError };
  });

  /* One-time setup + cleanup */
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      recRef.current?.abort();
    };
  }, []);

  /* Lazily build the recognition instance */
  function getRecognition() {
    if (!SR) return null;
    if (recRef.current) return recRef.current;

    const rec = new SR();
    rec.lang = "ar-SA";
    rec.continuous = false;
    rec.interimResults = true;
    rec.maxAlternatives = 1;

    rec.onresult = (event) => {
      if (!mounted.current) return;
      let interim = "";
      let final   = "";

      for (let i = event.resultIndex; i < event.results.length; i++) {
        const transcript = event.results[i][0].transcript;
        if (event.results[i].isFinal) final   += transcript;
        else                           interim += transcript;
      }

      if (interim) cbRef.current.onInterim?.(interim);
      if (final)   cbRef.current.onResult?.(final.trim());
    };

    rec.onerror = (event) => {
      if (!mounted.current) return;
      const msg = ERROR_MESSAGES[event.error];
      if (msg !== "") cbRef.current.onError?.(msg ?? `خطأ: ${event.error}`);
      if (mounted.current) setIsListening(false);
    };

    rec.onend = () => {
      if (mounted.current) setIsListening(false);
    };

    recRef.current = rec;
    return rec;
  }

  const start = useCallback(() => {
    const rec = getRecognition();
    if (!rec) return;
    try {
      rec.start();
      if (mounted.current) setIsListening(true);
    } catch {
      /* already started — ignore */
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const stop = useCallback(() => {
    recRef.current?.stop();
    if (mounted.current) setIsListening(false);
  }, []);

  const toggle = useCallback(() => {
    if (isListening) stop();
    else             start();
  }, [isListening, start, stop]);

  return {
    isListening,
    isSupported: SR !== null,
    toggle,
    stop,
    start,
  };
}
