import { useState, useCallback } from "react";
import { store } from "../core/persistence/store.js";

const STORE_KEY = "knowledge_entries";

const SEED = [
  {
    id:      "k1",
    title:   "تفضيلات هيكلة الملفات والتبعيات لوحدة 'core'...",
    preview: "يجب أن تكون الملفات الخاصة بوحدة 'core' في المسار...",
    when:    "عند هيكلة المشاريع",
    content: "يجب أن تكون الملفات الخاصة بوحدة 'core' في المسار الصحيح.",
    date:    "٤/٨",
    active:  true,
  },
  {
    id:      "k2",
    title:   "تفضيلات أولوية دمج المعلومات من المرفقات...",
    preview: "عندما يشدد المستخدم على أهمية مرفق معين...",
    when:    "عند دمج المرفقات",
    content: "عندما يشدد المستخدم على أهمية مرفق معين يجب إعطاؤه الأولوية.",
    date:    "١/١٤",
    active:  true,
  },
  {
    id:      "k3",
    title:   "تفضيلات تطوير مستند الأخلاقيات والسلامة...",
    preview: "عند تطوير مستند الأخلاقيات والسلامة...",
    when:    "عند كتابة وثائق السلامة",
    content: "عند تطوير مستند الأخلاقيات والسلامة يجب مراعاة المعايير الدولية.",
    date:    "١/١٢",
    active:  true,
  },
  {
    id:      "k4",
    title:   "تفضيلات البحث الاستباقي وإثراء أداة QB-Tools...",
    preview: "عند تطوير أو تعديل أداة QB-Tools أو أي أداة...",
    when:    "عند استخدام أدوات QB",
    content: "عند تطوير أو تعديل أداة QB-Tools أو أي أداة مشابهة.",
    date:    "٢٠٢٥/١٢/١٤",
    active:  true,
  },
];

function load() {
  return store.get(STORE_KEY) ?? SEED;
}

function save(entries) {
  store.set(STORE_KEY, entries);
}

const ID = () => `know_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;

function arabicDate() {
  const now = new Date();
  return `${now.getMonth() + 1}/${now.getDate()}`;
}

export function useKnowledge() {
  const [entries, setEntries] = useState(load);

  const addEntry = useCallback(({ name, when, content }) => {
    if (!when.trim() || !content.trim()) return false;
    const entry = {
      id:      ID(),
      title:   name.trim() || when.trim(),
      preview: content.trim().slice(0, 60) + (content.length > 60 ? "..." : ""),
      when:    when.trim(),
      content: content.trim(),
      date:    arabicDate(),
      active:  true,
    };
    const next = [entry, ...entries];
    setEntries(next);
    save(next);
    return true;
  }, [entries]);

  const deleteEntry = useCallback((id) => {
    const next = entries.filter(e => e.id !== id);
    setEntries(next);
    save(next);
  }, [entries]);

  const toggleEntry = useCallback((id) => {
    const next = entries.map(e => e.id === id ? { ...e, active: !e.active } : e);
    setEntries(next);
    save(next);
  }, [entries]);

  return { entries, addEntry, deleteEntry, toggleEntry };
}
