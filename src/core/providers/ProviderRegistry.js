import { OpenAIProvider } from "./OpenAIProvider.js";
import { ClaudeProvider } from "./ClaudeProvider.js";
import { LocalProvider }  from "./LocalProvider.js";

/**
 * AIRI Provider Registry
 *
 * Central factory and router for all model providers.
 * Manages active provider instance, health status cache,
 * and provider switching with teardown/setup lifecycle.
 */

/** Map of UI-visible model names → provider factory */
const PROVIDER_FACTORIES = {
  "Airi Cloud":     (cfg) => new OpenAIProvider({ ...cfg, modelId: "gpt-4o-mini" }),
  "GPT-4o":         (cfg) => new OpenAIProvider({ ...cfg, modelId: "gpt-4o"      }),
  "Claude Sonnet":  (cfg) => new ClaudeProvider({ ...cfg, modelId: "claude-sonnet-4-5" }),
  "Airi Local":     (cfg) => new LocalProvider(cfg),
};

export const MODEL_NAMES = Object.keys(PROVIDER_FACTORIES);

/** Provider capability flags for UI rendering decisions */
export function getProviderMeta(modelName) {
  const meta = {
    "Airi Cloud":    { icon: "cloud", requiresKey: true,  local: false, keyType: "openai"    },
    "GPT-4o":        { icon: "cloud", requiresKey: true,  local: false, keyType: "openai"    },
    "Claude Sonnet": { icon: "cloud", requiresKey: true,  local: false, keyType: "anthropic" },
    "Airi Local":    { icon: "cpu",   requiresKey: false, local: true,  keyType: null        },
  };
  return meta[modelName] ?? meta["Airi Cloud"];
}

class ProviderRegistry {
  constructor() {
    this._current   = null;
    this._modelName = "Airi Cloud";
    this._config    = {};
    this._statusCache = {};
  }

  /** Build and cache the active provider */
  _build(modelName, config) {
    const factory = PROVIDER_FACTORIES[modelName];
    if (!factory) {
      console.warn(`[ProviderRegistry] Unknown model: ${modelName}. Falling back to Airi Cloud.`);
      return PROVIDER_FACTORIES["Airi Cloud"](config);
    }
    return factory(config);
  }

  /**
   * Switch the active provider.
   * Aborts any in-flight request on the previous provider first.
   */
  switchTo(modelName, config = {}) {
    if (this._current) this._current.abort();
    this._modelName = modelName;
    this._config    = config;
    this._current   = this._build(modelName, config);
    return this._current;
  }

  /** Update API key or endpoint config without changing model */
  updateConfig(config = {}) {
    this._config = { ...this._config, ...config };
    if (this._current) this._current.abort();
    this._current = this._build(this._modelName, this._config);
  }

  /** Get current provider (creates one if none) */
  current() {
    if (!this._current) {
      this._current = this._build(this._modelName, this._config);
    }
    return this._current;
  }

  getCurrentModelName() { return this._modelName; }

  /** Abort current in-flight request */
  abort() { this._current?.abort(); }

  /**
   * Check health of all providers in parallel.
   * Returns { [modelName]: HealthResult } map.
   */
  async checkAllHealth(config = {}) {
    const entries = await Promise.all(
      MODEL_NAMES.map(async (name) => {
        const provider = this._build(name, config);
        try {
          const result = await provider.healthCheck();
          this._statusCache[name] = result;
          return [name, result];
        } catch (e) {
          const result = { available: false, reason: e.message };
          this._statusCache[name] = result;
          return [name, result];
        }
      })
    );
    return Object.fromEntries(entries);
  }

  /** Last known health status (no network call) */
  getCachedStatus(modelName) {
    return this._statusCache[modelName] ?? null;
  }
}

/** Singleton registry — shared across the app */
export const providerRegistry = new ProviderRegistry();
