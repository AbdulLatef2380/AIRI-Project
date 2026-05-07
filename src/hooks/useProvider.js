import { useCallback } from "react";
import { useApp } from "../context/useApp.js";
import { MODEL_NAMES, getProviderMeta } from "../core/providers/ProviderRegistry.js";

/**
 * useProvider
 *
 * Exposes model switching, API key management, and provider status
 * to UI components. Never import providerRegistry directly in screens.
 */
export function useProvider() {
  const {
    activeModel,
    apiKeys,
    localEndpoint,
    providerStatus,
    setModel,
    setApiKeys,
    setLocalEndpoint,
  } = useApp();

  const meta = getProviderMeta(activeModel);

  /** Switch the active model. Persists the selection. */
  const switchModel = useCallback((modelName) => {
    setModel(modelName);
  }, [setModel]);

  /** Save an OpenAI API key */
  const saveOpenAIKey = useCallback((key) => {
    setApiKeys({ openai: key.trim() });
  }, [setApiKeys]);

  /** Save an Anthropic API key */
  const saveAnthropicKey = useCallback((key) => {
    setApiKeys({ anthropic: key.trim() });
  }, [setApiKeys]);

  /** Save local endpoint URL */
  const saveLocalEndpoint = useCallback((url) => {
    setLocalEndpoint(url.trim() || "http://localhost:11434");
  }, [setLocalEndpoint]);

  /** Whether the current model has a usable API key */
  const hasRequiredKey = (() => {
    if (!meta.requiresKey) return true;
    if (meta.keyType === "openai")     return !!(apiKeys?.openai?.trim());
    if (meta.keyType === "anthropic")  return !!(apiKeys?.anthropic?.trim());
    return true;
  })();

  /** Cached health status for a given model name */
  const getStatus = useCallback((modelName) => {
    return providerStatus?.[modelName] ?? null;
  }, [providerStatus]);

  return {
    activeModel,
    models: MODEL_NAMES,
    meta,
    hasRequiredKey,
    apiKeys,
    localEndpoint,
    switchModel,
    saveOpenAIKey,
    saveAnthropicKey,
    saveLocalEndpoint,
    getStatus,
    providerStatus,
  };
}
