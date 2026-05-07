import { ProviderInterface } from "./ProviderInterface.js";
import { parseOllamaStream, parseOpenAIStream } from "../chat/StreamParser.js";

/**
 * Local inference provider.
 * Targets Ollama (primary) with llama.cpp OpenAI-compat endpoint fallback.
 *
 * Ollama:     http://localhost:11434
 * llama.cpp:  http://localhost:8080
 */
export class LocalProvider extends ProviderInterface {
  constructor(config = {}) {
    super(config);
    this.baseUrl  = config.localEndpoint ?? "http://localhost:11434";
    this.modelId  = config.modelId       ?? "llama3";
    this._backend = null; // "ollama" | "llamacpp" | null
  }

  getName()    { return "Airi Local"; }
  getModelId() { return this.modelId; }

  getCapabilities() {
    return { streaming: true, maxTokens: 2048, supportsTools: false, local: true, requiresKey: false };
  }

  async _detectBackend() {
    // Try Ollama first
    try {
      const r = await fetch(`${this.baseUrl}/api/tags`, {
        signal: AbortSignal.timeout(3000),
      });
      if (r.ok) {
        const data = await r.json();
        const models = data?.models ?? [];
        // Pick first available model if default isn't listed
        if (models.length > 0 && !models.some(m => m.name?.startsWith(this.modelId))) {
          this.modelId = models[0].name;
        }
        return "ollama";
      }
    } catch { /* not Ollama */ }

    // Try llama.cpp OpenAI-compatible endpoint
    try {
      const r = await fetch("http://localhost:8080/v1/models", {
        signal: AbortSignal.timeout(3000),
      });
      if (r.ok) {
        this.baseUrl = "http://localhost:8080";
        return "llamacpp";
      }
    } catch { /* not llama.cpp */ }

    return null;
  }

  async *stream(messages, options = {}) {
    if (!this._backend) {
      this._backend = await this._detectBackend();
    }

    if (!this._backend) {
      yield { error: "Local model not available. Start Ollama (ollama serve) or llama.cpp server on localhost." };
      return;
    }

    const signal = this._newSignal();

    if (this._backend === "ollama") {
      yield* this._streamOllama(messages, options, signal);
    } else {
      yield* this._streamLlamaCpp(messages, options, signal);
    }
  }

  async *_streamOllama(messages, options, signal) {
    const system = "You are AIRI, an intelligent AI assistant. Respond concisely. Support Arabic if the user writes in Arabic.";
    let response;
    try {
      response = await fetch(`${this.baseUrl}/api/chat`, {
        method:  "POST",
        signal,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          model:    this.modelId,
          messages: [{ role: "system", content: system }, ...messages],
          stream:   true,
          options:  { num_predict: options.maxTokens ?? 512 },
        }),
      });
    } catch (err) {
      if (err.name === "AbortError") { yield { aborted: true }; return; }
      this._backend = null; // reset so next call retries detection
      yield { error: `Local model error: ${err.message}` };
      return;
    }

    if (!response.ok) {
      this._backend = null;
      yield { error: `Ollama error ${response.status}` };
      return;
    }

    yield* parseOllamaStream(response);
  }

  async *_streamLlamaCpp(messages, options, signal) {
    // parseOpenAIStream already imported statically at top of file
    const system = "You are AIRI, an intelligent AI assistant.";
    let response;
    try {
      response = await fetch(`${this.baseUrl}/v1/chat/completions`, {
        method:  "POST",
        signal,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          model:      "local",
          messages:   [{ role: "system", content: system }, ...messages],
          stream:     true,
          max_tokens: options.maxTokens ?? 512,
        }),
      });
    } catch (err) {
      if (err.name === "AbortError") { yield { aborted: true }; return; }
      this._backend = null;
      yield { error: `llama.cpp error: ${err.message}` };
      return;
    }

    if (!response.ok) {
      this._backend = null;
      yield { error: `llama.cpp HTTP ${response.status}` };
      return;
    }

    yield* parseOpenAIStream(response);
  }

  async healthCheck() {
    const t0 = Date.now();
    const backend = await this._detectBackend();
    this._backend = backend;
    return {
      available: backend !== null,
      latency:   Date.now() - t0,
      reason:    backend ?? "No local model server detected on :11434 or :8080",
      backend,
      model:     this.modelId,
    };
  }
}
