/**
 * AIRI Provider Interface
 * All model providers implement this contract.
 * Never access provider logic directly from UI screens.
 */
export class ProviderInterface {
  constructor(config = {}) {
    this.config = config;
    this._abortController = null;
  }

  /** Human-readable name */
  getName() { return "Unknown"; }

  /** Model identifier used in API calls */
  getModelId() { return ""; }

  /**
   * Provider capabilities metadata.
   * Screens use this to conditionally render UI.
   */
  getCapabilities() {
    return {
      streaming:    true,
      maxTokens:    4096,
      supportsTools: false,
      local:        false,
      requiresKey:  true,
    };
  }

  /**
   * Stream a response for the given messages array.
   * Yields { token: string } for each partial token.
   * Yields { done: true, usage: { prompt: n, completion: n } } on finish.
   * Yields { error: string } on failure.
   *
   * @param {Array<{role:string, content:string}>} messages
   * @param {object} options
   * @returns {AsyncGenerator}
   */
  // eslint-disable-next-line require-yield
  async *stream(_messages, _options = {}) {
    yield { error: "Provider not implemented." };
  }

  /** Cancel the current in-flight request */
  abort() {
    if (this._abortController) {
      this._abortController.abort();
      this._abortController = null;
    }
  }

  /** Check if the provider endpoint is reachable */
  async healthCheck() {
    return { available: false, latency: 0, reason: "not implemented" };
  }

  /** Create a fresh AbortController and return its signal */
  _newSignal() {
    this.abort();
    this._abortController = new AbortController();
    return this._abortController.signal;
  }
}
