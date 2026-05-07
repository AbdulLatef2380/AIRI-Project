import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App.jsx";

/**
 * Mount without StrictMode to prevent double-invocation of effects
 * which causes duplicate health checks and async race conditions
 * in the provider registry.
 */
createRoot(document.getElementById("root")).render(<App />);
