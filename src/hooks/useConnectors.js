import { useCallback } from "react";
import { useApp } from "../context/useApp.js";

/**
 * useConnectors
 *
 * Provides connector list, toggle actions, and status.
 * All mutations go through AppContext → ConnectorRegistry → localStorage.
 */
export function useConnectors() {
  const { connectors, toggleConnector, refreshConnectors } = useApp();

  const connected  = connectors.filter(c => c.category === "connected");
  const available  = connectors.filter(c => c.category === "available");

  const toggle = useCallback((id) => {
    toggleConnector(id);
  }, [toggleConnector]);

  const refresh = useCallback(() => {
    refreshConnectors();
  }, [refreshConnectors]);

  return { connectors, connected, available, toggle, refresh };
}
