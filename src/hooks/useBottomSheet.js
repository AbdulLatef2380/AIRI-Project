import { useState, useCallback } from "react";

/**
 * Lightweight hook for managing a single bottom-sheet's open/close state.
 * Returns { open, show, hide, toggle } so the caller never touches raw
 * setState directly.
 */
export function useBottomSheet(initial = false) {
  const [open, setOpen] = useState(initial);
  const show   = useCallback(() => setOpen(true),  []);
  const hide   = useCallback(() => setOpen(false), []);
  const toggle = useCallback(() => setOpen(v => !v), []);
  return { open, show, hide, toggle };
}
