import { ProviderInterface } from "./ProviderInterface.js";
import { parseOpenAIStream } from "../chat/StreamParser.js";

const BASE_URL = "https://api.openai.com/v1";

export class OpenAIProvider extends ProviderInterface {
  constructor(config = {}) {
    super(config);
    this.apiKey  = config.apiKey  ?? "";
    this.modelId = config.modelId ?? "gpt-4o";
  }

  getName()    { return "GPT-4o"; }
  getModelId() { return this.modelId; }

  getCapabilities() {
    return { streaming: true, maxTokens: 4096, supportsTools: false, local: false, requiresKey: true };
  }

  async *stream(messages, options = {}) {
    if (!this.apiKey) {
      yield { error: "OpenAI API key is not configured. Tap the model selector to add one." };
      return;
    }

    const signal = this._newSignal();
    const systemMsg = {
      role:    "system",
      content: "You are AIRI, an intelligent AI assistant. Respond concisely and helpfully. Support Arabic if the user writes in Arabic.",
    };

    let response;
    try {
      response = await fetch(`${BASE_URL}/chat/completions`, {
        method:  "POST",
        signal,
        headers: {
          "Content-Type":  "application/json",
          "Authorization": `Bearer ${this.apiKey}`,
        },
        body: JSON.stringify({
          model:       this.modelId,
          messages:    [systemMsg, ...messages],
          stream:      true,
          max_tokens:  options.maxTokens ?? 1024,
          temperature: options.temperature ?? 0.7,
        }),
      });
    } catch (err) {
      if (err.name === "AbortError") { yield { aborted: true }; return; }
      yield { error: `Network error: ${err.message}` };
      return;
    }

    if (!response.ok) {
      let msg = `OpenAI error ${response.status}`;
      try { const j = await response.json(); msg = j.error?.message ?? msg; } catch {}
      yield { error: msg };
      return;
    }

    yield* parseOpenAIStream(response);
  }

  async healthCheck() {
    if (!this.apiKey) return { available: false, reason: "No API key" };
    const t0 = Date.now();
    try {
      const r = await fetch(`${BASE_URL}/models`, {
        headers: { "Authorization": `Bearer ${this.apiKey}` },
        signal: AbortSignal.timeout(5000),
      });
      return { available: r.ok, latency: Date.now() - t0, reason: r.ok ? "ok" : `HTTP ${r.status}` };
    } catch (e) {
      return { available: false, latency: Date.now() - t0, reason: e.message };
    }
  }
}
