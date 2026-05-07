import { providerRegistry } from "../providers/ProviderRegistry.js";

/**
 * AIRI Chat Engine
 *
 * Orchestrates message flow between UI and providers.
 * Handles: streaming, abort, retry, queueing, token accounting.
 *
 * Usage:
 *   const engine = new ChatEngine();
 *   for await (const event of engine.send(messages, modelName, config)) {
 *     // { token, done, error, aborted, usage }
 *   }
 */

const MAX_RETRIES = 2;
const RETRY_DELAY = 800; // ms

function sleep(ms) {
  return new Promise(res => setTimeout(res, ms));
}

export class ChatEngine {
  constructor() {
    this._busy   = false;
    this._queue  = [];
  }

  get isBusy() { return this._busy; }

  /** Cancel the current in-flight request */
  abort() {
    providerRegistry.abort();
  }

  /**
   * Send a message and stream the response.
   * Returns an AsyncGenerator that yields streaming events.
   *
   * @param {Array<{role,content}>} messages  Full conversation history
   * @param {string}                modelName Active model name
   * @param {object}                config    Provider config (apiKeys, endpoint…)
   * @param {object}                options   { maxTokens, temperature }
   */
  async *send(messages, modelName, config = {}, options = {}) {
    if (this._busy) {
      yield { error: "Another request is in progress. Please wait." };
      return;
    }

    this._busy = true;
    const provider = providerRegistry.switchTo(modelName, config);

    let attempt = 0;
    while (attempt <= MAX_RETRIES) {
      let hadToken  = false;
      let gotError  = false;
      let errorMsg  = "";

      try {
        for await (const event of provider.stream(messages, options)) {
          if (event.aborted) { yield { aborted: true }; this._busy = false; return; }
          if (event.error) {
            gotError = true;
            errorMsg = event.error;
            break; // will decide below whether to retry
          }
          if (event.token) { hadToken = true; yield event; }
          if (event.done)  { yield event; this._busy = false; return; }
        }
      } catch (err) {
        gotError = true;
        errorMsg = err.message ?? "Unknown error";
      }

      if (!gotError) { this._busy = false; return; }

      const isRetryable = !hadToken &&
        !errorMsg.includes("API key") &&
        !errorMsg.includes("CORS") &&
        !errorMsg.includes("not configured");

      if (isRetryable && attempt < MAX_RETRIES) {
        attempt++;
        yield { retrying: attempt, maxRetries: MAX_RETRIES };
        await sleep(RETRY_DELAY * attempt);
        continue;
      }

      yield { error: errorMsg };
      break;
    }

    this._busy = false;
  }
}

/** Singleton engine — one per app */
export const chatEngine = new ChatEngine();
