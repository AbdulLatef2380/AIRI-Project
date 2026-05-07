import { store } from "../persistence/store.js";

/**
 * AIRI Connector Registry
 *
 * Defines all available connectors, their metadata, and manages
 * enabled/disabled state with localStorage persistence.
 */

/** Static connector definitions — metadata only, no secrets here */
export const CONNECTOR_DEFINITIONS = [
  {
    id:      "gmail",
    name:    "Gmail",
    icon:    "mail",
    color:   "#ea4335",
    bg:      "#ea433522",
    category: "connected",
    authType: "oauth2",
    scopes:   ["https://mail.google.com/"],
    description: "إرسال واستقبال البريد الإلكتروني",
  },
  {
    id:      "github",
    name:    "GitHub",
    icon:    "github",
    color:   "#f0f0f0",
    bg:      "#f0f0f022",
    category: "connected",
    authType: "oauth2",
    description: "إدارة المستودعات وتتبع الكود",
  },
  {
    id:      "openai",
    name:    "OpenAI",
    icon:    "openai",
    color:   "#10a37f",
    bg:      "#10a37f22",
    category: "connected",
    authType: "apikey",
    description: "الوصول إلى نماذج GPT",
  },
  {
    id:      "browser",
    name:    "متصفحي",
    icon:    "globe",
    color:   "#7c5fff",
    bg:      "#7c5fff22",
    category: "available",
    authType: "none",
    description: "البحث في الإنترنت",
  },
  {
    id:      "gcal",
    name:    "تقويم Google",
    icon:    "calendar",
    color:   "#4285f4",
    bg:      "#4285f422",
    category: "available",
    authType: "oauth2",
    description: "قراءة وإنشاء أحداث التقويم",
  },
  {
    id:      "gdrive",
    name:    "جوجل درايف",
    icon:    "files",
    color:   "#fbbc04",
    bg:      "#fbbc0422",
    category: "available",
    authType: "oauth2",
    description: "الوصول إلى الملفات والمجلدات",
  },
  {
    id:      "outlook_mail",
    name:    "بريد Outlook",
    icon:    "mail",
    color:   "#0078d4",
    bg:      "#0078d422",
    category: "available",
    authType: "oauth2",
    description: "بريد Microsoft Outlook",
  },
  {
    id:      "outlook_cal",
    name:    "تقويم Outlook",
    icon:    "calendar",
    color:   "#0078d4",
    bg:      "#0078d422",
    category: "available",
    authType: "oauth2",
    description: "تقويم Microsoft Outlook",
  },
];

/** Default enabled state for built-in connectors */
const DEFAULT_ENABLED = { gmail: true, github: true, openai: true };

class ConnectorRegistryClass {
  constructor() {
    this._persisted = store.get("connectors") ?? {};
  }

  _getEnabled(id) {
    if (id in this._persisted) return this._persisted[id];
    return DEFAULT_ENABLED[id] ?? false;
  }

  _persist() {
    store.set("connectors", this._persisted);
  }

  /** Returns all connectors with live enabled state */
  getAll() {
    return CONNECTOR_DEFINITIONS.map(def => ({
      ...def,
      enabled: this._getEnabled(def.id),
    }));
  }

  /** Returns only connected (category=connected) connectors */
  getConnected() {
    return this.getAll().filter(c => c.category === "connected");
  }

  /** Returns available (not yet connected) connectors */
  getAvailable() {
    return this.getAll().filter(c => c.category === "available");
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

  /** Health status — stubbed for future OAuth/ping checks */
  async getStatus(id) {
    return { id, connected: this._getEnabled(id), lastSync: null };
  }
}

export const ConnectorRegistry = new ConnectorRegistryClass();
