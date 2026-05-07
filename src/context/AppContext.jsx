import { createContext, useReducer, useEffect, useCallback } from "react";
import { store } from "../core/persistence/store.js";
import { providerRegistry } from "../core/providers/ProviderRegistry.js";
import { ConnectorRegistry } from "../core/connectors/ConnectorRegistry.js";
import { SkillRegistry }    from "../core/skills/SkillRegistry.js";

export const AppContext = createContext(null);

/* ── Reducer ────────────────────────────────────────────────────── */
function reducer(state, action) {
  switch (action.type) {

    case "SET_MODEL":
      return { ...state, activeModel: action.model };

    case "SET_API_KEYS":
      return { ...state, apiKeys: { ...state.apiKeys, ...action.keys } };

    case "SET_LOCAL_ENDPOINT":
      return { ...state, localEndpoint: action.endpoint };

    case "SET_PROVIDER_STATUS":
      return { ...state, providerStatus: { ...state.providerStatus, ...action.status } };

    case "UPSERT_CONVERSATION": {
      const exists = state.conversations.some(c => c.id === action.conversation.id);
      const conversations = exists
        ? state.conversations.map(c => c.id === action.conversation.id ? action.conversation : c)
        : [...state.conversations, action.conversation];
      return { ...state, conversations };
    }

    case "DELETE_CONVERSATION":
      return { ...state, conversations: state.conversations.filter(c => c.id !== action.id) };

    case "SET_CONNECTORS":
      return { ...state, connectors: action.connectors };

    case "SET_SKILLS":
      return { ...state, skills: action.skills };

    case "CLEAR_CACHE":
      store.clear();
      return init();

    default:
      return state;
  }
}

function init() {
  const persisted = store.hydrate();
  return {
    activeModel:    persisted.activeModel,
    apiKeys:        persisted.apiKeys,
    localEndpoint:  persisted.localEndpoint,
    conversations:  persisted.conversations,
    connectors:     ConnectorRegistry.getAll(),
    skills:         SkillRegistry.getAll(),
    providerStatus: {},
    settings:       persisted.settings,
  };
}

/* ── Provider component ─────────────────────────────────────────── */
export function AppProvider({ children }) {
  const [state, dispatch] = useReducer(reducer, null, init);

  /* Persist activeModel on change */
  useEffect(() => {
    store.set("activeModel", state.activeModel);
    providerRegistry.updateConfig({
      apiKey:        state.apiKeys?.openai     ?? "",
      anthropicKey:  state.apiKeys?.anthropic  ?? "",
      localEndpoint: state.localEndpoint,
    });
  }, [state.activeModel, state.apiKeys, state.localEndpoint]);

  /* Persist conversations on change */
  useEffect(() => {
    store.set("conversations", state.conversations);
  }, [state.conversations]);

  /* Run health checks once on mount */
  useEffect(() => {
    const cfg = {
      apiKey:       state.apiKeys?.openai    ?? "",
      anthropicKey: state.apiKeys?.anthropic ?? "",
      localEndpoint: state.localEndpoint,
    };
    providerRegistry.checkAllHealth(cfg).then(status => {
      dispatch({ type: "SET_PROVIDER_STATUS", status });
    }).catch(() => {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /* ── Action creators ── */
  const setModel = useCallback((model) => {
    dispatch({ type: "SET_MODEL", model });
  }, []);

  const setApiKeys = useCallback((keys) => {
    dispatch({ type: "SET_API_KEYS", keys });
    store.merge("apiKeys", keys);
  }, []);

  const setLocalEndpoint = useCallback((endpoint) => {
    dispatch({ type: "SET_LOCAL_ENDPOINT", endpoint });
    store.set("localEndpoint", endpoint);
  }, []);

  const upsertConversation = useCallback((conversation) => {
    dispatch({ type: "UPSERT_CONVERSATION", conversation });
  }, []);

  const deleteConversation = useCallback((id) => {
    dispatch({ type: "DELETE_CONVERSATION", id });
  }, []);

  const refreshConnectors = useCallback(() => {
    dispatch({ type: "SET_CONNECTORS", connectors: ConnectorRegistry.getAll() });
  }, []);

  const toggleConnector = useCallback((id) => {
    ConnectorRegistry.toggle(id);
    refreshConnectors();
  }, [refreshConnectors]);

  const refreshSkills = useCallback(() => {
    dispatch({ type: "SET_SKILLS", skills: SkillRegistry.getAll() });
  }, []);

  const toggleSkill = useCallback((id) => {
    SkillRegistry.toggle(id);
    refreshSkills();
  }, [refreshSkills]);

  const clearCache = useCallback(() => {
    dispatch({ type: "CLEAR_CACHE" });
  }, []);

  const value = {
    ...state,
    setModel,
    setApiKeys,
    setLocalEndpoint,
    upsertConversation,
    deleteConversation,
    toggleConnector,
    refreshConnectors,
    toggleSkill,
    refreshSkills,
    clearCache,
  };

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

