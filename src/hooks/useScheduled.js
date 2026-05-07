import { useState, useCallback } from "react";
import { store } from "../core/persistence/store.js";

const STORE_KEY = "scheduled_tasks";

const SEED_TASKS = [
  { id: "t1", title: "تحليل تقرير المبيعات",    time: "يوميًا، 9:00 ص",     status: "scheduled" },
  { id: "t2", title: "مراجعة البريد الإلكتروني", time: "كل ساعة",            status: "scheduled" },
  { id: "t3", title: "نسخ احتياطي للمشروع",      time: "أسبوعيًا، الاثنين", status: "complete"  },
  { id: "t4", title: "إرسال ملخص أسبوعي",        time: "الجمعة 5:00 م",      status: "complete"  },
];

function load() {
  return store.get(STORE_KEY) ?? SEED_TASKS;
}

function save(tasks) {
  store.set(STORE_KEY, tasks);
}

const ID = () => `task_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;

export function useScheduled() {
  const [tasks, setTasks] = useState(load);

  const addTask = useCallback((title, time) => {
    if (!title.trim()) return;
    const next = [
      { id: ID(), title: title.trim(), time: time.trim() || "مرة واحدة", status: "scheduled" },
      ...tasks,
    ];
    setTasks(next);
    save(next);
  }, [tasks]);

  const completeTask = useCallback((id) => {
    const next = tasks.map(t => t.id === id ? { ...t, status: "complete" } : t);
    setTasks(next);
    save(next);
  }, [tasks]);

  const deleteTask = useCallback((id) => {
    const next = tasks.filter(t => t.id !== id);
    setTasks(next);
    save(next);
  }, [tasks]);

  const scheduled = tasks.filter(t => t.status === "scheduled");
  const completed  = tasks.filter(t => t.status === "complete");

  return { tasks, scheduled, completed, addTask, completeTask, deleteTask };
}
