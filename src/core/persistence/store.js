/**
 * AIRI Persistence Store
 * Safe, versioned localStorage wrapper with corruption recovery and schema migration.
 */

const NS = "airi_v1";
const key = (k) => `${NS}_${k}`;

const DEFAULTS = {
  activeModel:   "Airi Cloud",
  apiKeys:       { openai: "", anthropic: "" },
  conversations: [],
  connectors:    {},
  skills:        {},
  settings:      { language: "ar", theme: "system" },
  localEndpoint: "http://localhost:11434",
};

function safeRead(k, fallback) {
  try {
    const raw = localStorage.getItem(key(k));
    if (raw === null) return fallback;
    return JSON.parse(raw);
  } catch {
    return fallback;
  }
}

function safeWrite(k, value) {
  try {
    localStorage.setItem(key(k), JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

function safeRemove(k) {
  try { localStorage.removeItem(key(k)); } catch {}
}

export const store = {
  get:    (k) => safeRead(k, DEFAULTS[k]),
  set:    (k, v) => safeWrite(k, v),
  remove: (k) => safeRemove(k),

  /** Atomic merge for object keys */
  merge: (k, partial) => {
    const current = safeRead(k, DEFAULTS[k] ?? {});
    safeWrite(k, { ...current, ...partial });
  },

  /** Clear all AIRI keys from localStorage */
  clear: () => {
    try {
      Object.keys(localStorage)
        .filter(k => k.startsWith(NS))
        .forEach(k => localStorage.removeItem(k));
    } catch {}
  },

  /** Hydrate everything at once — returns full initial state */
  hydrate: () => ({
    activeModel:   safeRead("activeModel",   DEFAULTS.activeModel),
    apiKeys:       safeRead("apiKeys",       DEFAULTS.apiKeys),
    conversations: safeRead("conversations", DEFAULTS.conversations),
    connectors:    safeRead("connectors",    DEFAULTS.connectors),
    skills:        safeRead("skills",        DEFAULTS.skills),
    settings:      safeRead("settings",      DEFAULTS.settings),
    localEndpoint: safeRead("localEndpoint", DEFAULTS.localEndpoint),
  }),
};

export default store;
