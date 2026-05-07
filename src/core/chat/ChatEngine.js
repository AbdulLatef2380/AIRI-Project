import { providerRegistry } from "../providers/ProviderRegistry.js";

/**
 * AIRI Chat Engine
 *
 * Orchestrates message flow between UI and providers.
 * Handles: streaming, abort, retry, queueing, token accounting.
 *
 * Usage:
 *   for await (const event of chatEngine.send(messages, modelName, config)) {
 *     // { token, done, error, aborted, usage, retrying }
 *   }
 */

const MAX_RETRIES = 2;
const RETRY_DELAY = 800; // ms

function sleep(ms) {
  return new Promise(res => setTimeout(res, ms));
}

export class ChatEngine {
  constructor() {
    this._busy = false;
  }

  get isBusy() { return this._busy; }

  /** Cancel the current in-flight request */
  abort() {
    providerRegistry.abort();
  }

  /** Force-reset the busy flag (recovery from stuck state) */
  reset() {
    this._busy = false;
    providerRegistry.abort();
  }

  /**
   * Send messages and stream the response.
   * Async generator — yields streaming events.
   */
  async *send(messages, modelName, config = {}, options = {}) {
    /* If already busy, abort the previous request and wait briefly */
    if (this._busy) {
      providerRegistry.abort();
      await sleep(150);
    }

    this._busy = true;
    let provider;
    try {
      provider = providerRegistry.switchTo(modelName, config);
    } catch (err) {
      this._busy = false;
      yield { error: `Provider error: ${err.message}` };
      return;
    }

    let attempt = 0;

    while (attempt <= MAX_RETRIES) {
      let hadToken = false;
      let gotError = false;
      let errorMsg = "";

      try {
        for await (const event of provider.stream(messages, options)) {
          if (event.aborted) {
            this._busy = false;
            yield { aborted: true };
            return;
          }
          if (event.error) {
            gotError = true;
            errorMsg = event.error;
            break;
          }
          if (event.token) {
            hadToken = true;
            yield event;
          }
          if (event.done) {
            this._busy = false;
            yield event;
            return;
          }
        }
      } catch (err) {
        gotError = true;
        errorMsg = err.name === "AbortError" ? "aborted" : (err.message ?? "Unknown error");
      }

      if (errorMsg === "aborted") {
        this._busy = false;
        yield { aborted: true };
        return;
      }

      if (!gotError) {
        this._busy = false;
        return;
      }

      const isRetryable =
        !hadToken &&
        !errorMsg.includes("API key") &&
        !errorMsg.includes("not configured") &&
        !errorMsg.includes("CORS") &&
        !errorMsg.includes("key is not");

      if (isRetryable && attempt < MAX_RETRIES) {
        attempt++;
        yield { retrying: attempt, maxRetries: MAX_RETRIES };
        await sleep(RETRY_DELAY * attempt);
        /* Re-build provider for retry */
        try { provider = providerRegistry.switchTo(modelName, config); } catch {}
        continue;
      }

      this._busy = false;
      yield { error: errorMsg };
      return;
    }

    this._busy = false;
  }
}

/** Singleton engine — one per app */
export const chatEngine = new ChatEngine();
