/**
 * SSE / NDJSON stream parser utilities.
 * Converts a fetch ReadableStream into an async token generator.
 */

/**
 * Parse OpenAI-style SSE stream.
 * Format: `data: {"choices":[{"delta":{"content":"..."}}]}\n\n`
 */
export async function* parseOpenAIStream(response) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed || !trimmed.startsWith("data: ")) continue;
        const data = trimmed.slice(6);
        if (data === "[DONE]") { yield { done: true }; return; }
        try {
          const json = JSON.parse(data);
          const token = json?.choices?.[0]?.delta?.content;
          const usage = json?.usage;
          if (token) yield { token };
          if (json?.choices?.[0]?.finish_reason === "stop") {
            yield { done: true, usage: usage ?? null };
            return;
          }
        } catch { /* malformed chunk — skip */ }
      }
    }
  } finally {
    reader.releaseLock();
  }
  yield { done: true };
}

/**
 * Parse Anthropic-style SSE stream.
 * Format: `data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}`
 */
export async function* parseAnthropicStream(response) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let inputTokens = 0;
  let outputTokens = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed.startsWith("data: ")) continue;
        const data = trimmed.slice(6);
        try {
          const json = JSON.parse(data);
          if (json.type === "content_block_delta" && json.delta?.type === "text_delta") {
            yield { token: json.delta.text };
          }
          if (json.type === "message_start" && json.message?.usage) {
            inputTokens = json.message.usage.input_tokens ?? 0;
          }
          if (json.type === "message_delta" && json.usage) {
            outputTokens = json.usage.output_tokens ?? 0;
          }
          if (json.type === "message_stop") {
            yield { done: true, usage: { prompt: inputTokens, completion: outputTokens } };
            return;
          }
        } catch { /* skip */ }
      }
    }
  } finally {
    reader.releaseLock();
  }
  yield { done: true };
}

/**
 * Parse Ollama NDJSON stream.
 * Format: `{"model":"...","response":"...","done":false}`
 */
export async function* parseOllamaStream(response) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;
        try {
          const json = JSON.parse(trimmed);
          if (json.response) yield { token: json.response };
          if (json.done) {
            yield {
              done: true,
              usage: {
                prompt:     json.prompt_eval_count ?? 0,
                completion: json.eval_count ?? 0,
              },
            };
            return;
          }
        } catch { /* skip */ }
      }
    }
  } finally {
    reader.releaseLock();
  }
  yield { done: true };
}
