import { store } from "../persistence/store.js";

/**
 * AIRI Skill Registry
 *
 * Defines all available skills, manages enabled state with persistence,
 * and provides execution lifecycle hooks for the chat engine.
 */

export const SKILL_DEFINITIONS = [
  {
    id:       "continuous_compute",
    name:     "الحوسبة المستمرة",
    desc:     "يجب القراءة عندما يحتاج المستخدم إلى تشغيل خدمات مستمرة...",
    official: true,
    date:     "٣٠ أبريل ٢٠٢٦",
    category: "system",
    /** Inject into system prompt when active */
    systemPromptFragment: "You can help users set up and monitor persistent background services.",
    /** Tool definitions exposed to the LLM */
    tools: [],
  },
  {
    id:       "music_generator",
    name:     "موجه الموسيقى",
    desc:     "يولّد موسيقى بناءً على وصف المستخدم...",
    official: false,
    date:     "١٢ يناير",
    category: "creative",
    systemPromptFragment: "You can generate music prompts and help users create music.",
    tools: [],
  },
  {
    id:       "code_analyzer",
    name:     "محلل الكود المتقدم",
    desc:     "يحلل الأكواد البرمجية ويقترح تحسينات...",
    official: true,
    date:     "٥ مارس",
    category: "dev",
    systemPromptFragment: "You are an expert code reviewer. Analyze code for bugs, performance, and style.",
    tools: [],
  },
];

/** Merge enabled skills' system prompt fragments into one string */
export function buildSystemPromptFromSkills(skillIds) {
  return SKILL_DEFINITIONS
    .filter(s => skillIds.includes(s.id))
    .map(s => s.systemPromptFragment)
    .filter(Boolean)
    .join("\n");
}

const DEFAULT_ENABLED = {};

class SkillRegistryClass {
  constructor() {
    this._persisted = store.get("skills") ?? {};
  }

  _getEnabled(id) {
    if (id in this._persisted) return this._persisted[id];
    return DEFAULT_ENABLED[id] ?? false;
  }

  _persist() {
    store.set("skills", this._persisted);
  }

  getAll() {
    return SKILL_DEFINITIONS.map(def => ({
      ...def,
      on: this._getEnabled(def.id),
    }));
  }

  toggle(id) {
    const current = this._getEnabled(id);
    this._persisted[id] = !current;
    this._persist();
    return !current;
  }

  enable(id)  { this._persisted[id] = true;  this._persist(); }
  disable(id) { this._persisted[id] = false; this._persist(); }
  isEnabled(id) { return this._getEnabled(id); }

  /** IDs of all currently-enabled skills */
  getEnabledIds() {
    return SKILL_DEFINITIONS.filter(s => this._getEnabled(s.id)).map(s => s.id);
  }
}

export const SkillRegistry = new SkillRegistryClass();
