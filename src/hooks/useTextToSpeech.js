import { useState, useCallback, useRef, useEffect } from "react";
import { store } from "../core/persistence/store.js";

/**
 * useTextToSpeech
 *
 * Browser-native Web Speech Synthesis — no model download required.
 * Works like ChatGPT/Gemini: bundled at the system level via the browser.
 *
 * Supports Arabic (ar-SA) automatically.
 * State: enabled/disabled persisted to localStorage.
 *
 * Returns:
 *   speak(text, lang?)  — speak a string
 *   stop()              — cancel current speech
 *   isSpeaking          — true while speaking
 *   isEnabled           — whether voice output is on
 *   toggle()            — flip enabled state
 *   isSupported         — false if browser doesn't have speechSynthesis
 */

const SYNTH = typeof window !== "undefined" ? window.speechSynthesis : null;
const STORE_KEY = "ttsEnabled";

/* Best available voice for a given BCP-47 lang prefix */
function pickVoice(lang = "ar") {
  if (!SYNTH) return null;
  const voices = SYNTH.getVoices();
  return (
    voices.find(v => v.lang === lang + "-SA") ||   // exact Arabic Saudi
    voices.find(v => v.lang.startsWith(lang))  ||  // any Arabic
    voices.find(v => v.default)                ||  // device default
    voices[0]                                  ||  // first available
    null
  );
}

export function useTextToSpeech() {
  const [isSpeaking, setIsSpeaking] = useState(false);
  const [isEnabled, setIsEnabled]   = useState(() => store.get(STORE_KEY) ?? true);
  const utterRef = useRef(null);
  const supported = SYNTH !== null;

  /* Load voices — Chrome loads them async */
  useEffect(() => {
    if (!SYNTH) return;
    /* getVoices() may be empty until this event fires */
    const handler = () => {};
    SYNTH.addEventListener("voiceschanged", handler);
    return () => {
      SYNTH.removeEventListener("voiceschanged", handler);
      SYNTH.cancel();
    };
  }, []);

  /**
   * Speak text. Pass lang="en" for English, "ar" for Arabic.
   * Language is auto-detected if omitted.
   */
  const speak = useCallback((text, lang) => {
    if (!supported || !isEnabled || !text?.trim()) return;

    /* Detect language if not provided */
    const autoLang = lang ?? (/[\u0600-\u06FF]/.test(text) ? "ar" : "en");

    SYNTH.cancel(); /* stop anything currently playing */

    const utterance = new SpeechSynthesisUtterance(text.trim());
    utterance.lang   = autoLang === "ar" ? "ar-SA" : "en-US";
    utterance.rate   = autoLang === "ar" ? 0.88 : 0.92;
    utterance.pitch  = 1.0;
    utterance.volume = 1.0;

    /* Assign best available voice */
    const voice = pickVoice(autoLang);
    if (voice) utterance.voice = voice;

    utterance.onstart = () => setIsSpeaking(true);
    utterance.onend   = () => setIsSpeaking(false);
    utterance.onerror = (e) => {
      if (e.error !== "interrupted") console.warn("[TTS] error:", e.error);
      setIsSpeaking(false);
    };

    utterRef.current = utterance;
    SYNTH.speak(utterance);
  }, [supported, isEnabled]);

  const stop = useCallback(() => {
    SYNTH?.cancel();
    setIsSpeaking(false);
  }, []);

  const toggle = useCallback(() => {
    setIsEnabled(prev => {
      const next = !prev;
      store.set(STORE_KEY, next);
      if (!next) SYNTH?.cancel();
      return next;
    });
  }, []);

  return {
    speak,
    stop,
    isSpeaking,
    isEnabled,
    toggle,
    isSupported: supported,
  };
}
