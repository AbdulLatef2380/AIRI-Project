import { useState, useCallback } from "react";
import { useApp } from "../context/useApp.js";

/**
 * useSkills
 *
 * Provides skills list with search/filter, toggle actions.
 * All mutations go through AppContext → SkillRegistry → localStorage.
 */
export function useSkills() {
  const { skills, toggleSkill } = useApp();
  const [search, setSearch] = useState("");

  const filtered = search.trim()
    ? skills.filter(s => s.name.includes(search) || s.desc.includes(search))
    : skills;

  const toggle = useCallback((id) => {
    toggleSkill(id);
  }, [toggleSkill]);

  return { skills, filtered, search, setSearch, toggle };
}
