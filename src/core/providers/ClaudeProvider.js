import { ProviderInterface } from "./ProviderInterface.js";
import { parseAnthropicStream } from "../chat/StreamParser.js";

/**
 * Claude provider.
 * NOTE: Anthropic's API does NOT support browser-side CORS requests.
 * In production, route through a server-side proxy at /api/proxy/anthropic.
 * The provider automatically uses the proxy if VITE_ANTHROPIC_PROXY is set,
 * otherwise it attempts direct (will fail in browser with CORS error —
 * caught gracefully).
 */
const DIRECT_URL = "https://api.anthropic.com/v1/messages";
const PROXY_URL  = typeof import.meta !== "undefined"
  ? (import.meta.env?.VITE_ANTHROPIC_PROXY ?? null)
  : null;

export class ClaudeProvider extends ProviderInterface {
  constructor(config = {}) {
    super(config);
    this.apiKey  = config.apiKey  ?? "";
    this.modelId = config.modelId ?? "claude-sonnet-4-5";
  }

  getName()    { return "Claude Sonnet"; }
  getModelId() { return this.modelId; }

  getCapabilities() {
    return { streaming: true, maxTokens: 4096, supportsTools: false, local: false, requiresKey: true };
  }

  async *stream(messages, options = {}) {
    if (!this.apiKey) {
      yield { error: "Anthropic API key is not configured. Tap the model selector to add one." };
      return;
    }

    const endpoint = PROXY_URL ?? DIRECT_URL;
    const signal   = this._newSignal();

    const body = {
      model:      this.modelId,
      max_tokens: options.maxTokens ?? 1024,
      system:     "You are AIRI, an intelligent AI assistant. Respond concisely and helpfully. Support Arabic if the user writes in Arabic.",
      messages:   messages.map(m => ({ role: m.role, content: m.content })),
      stream:     true,
    };

    let response;
    try {
      const headers = { "Content-Type": "application/json" };
      if (PROXY_URL) {
        headers["X-Api-Key"] = this.apiKey;
      } else {
        headers["x-api-key"]         = this.apiKey;
        headers["anthropic-version"]  = "2023-06-01";
        headers["anthropic-dangerous-direct-browser-access"] = "true";
      }

      response = await fetch(endpoint, { method: "POST", signal, headers, body: JSON.stringify(body) });
    } catch (err) {
      if (err.name === "AbortError") { yield { aborted: true }; return; }
      if (err.message?.includes("CORS") || err.message?.includes("Failed to fetch")) {
        yield { error: "Claude requires a server-side proxy due to browser CORS restrictions. Set VITE_ANTHROPIC_PROXY in your environment." };
      } else {
        yield { error: `Network error: ${err.message}` };
      }
      return;
    }

    if (!response.ok) {
      let msg = `Anthropic error ${response.status}`;
      try { const j = await response.json(); msg = j.error?.message ?? msg; } catch {}
      yield { error: msg };
      return;
    }

    yield* parseAnthropicStream(response);
  }

  async healthCheck() {
    if (!this.apiKey) return { available: false, reason: "No API key" };
    return { available: true, latency: 0, reason: "Key present (CORS check skipped)" };
  }
}
