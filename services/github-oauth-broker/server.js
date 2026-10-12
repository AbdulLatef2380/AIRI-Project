import http from "node:http";
import crypto from "node:crypto";

const PORT = Number(process.env.PORT || 8080);
const APP_SLUG = required("GITHUB_APP_SLUG");
const APP_ID = required("GITHUB_APP_ID");
const PRIVATE_KEY = required("GITHUB_APP_PRIVATE_KEY").replace(/\\n/g, "\n");
const PUBLIC_REDIRECT = required("AIRI_CALLBACK_URI");
const GITHUB = "https://api.github.com";
const transactions = new Map();
const tickets = new Map();
const sessions = new Map();

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}
function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}
function random() { return crypto.randomBytes(32).toString("base64url"); }
function safeEqual(a, b) {
  return typeof a === "string" && typeof b === "string" && a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
}
async function body(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
}
function jwt() {
  const now = Math.floor(Date.now() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(JSON.stringify({ iat: now - 60, exp: now + 540, iss: APP_ID })).toString("base64url");
  const unsigned = `${header}.${payload}`;
  const signature = crypto.createSign("RSA-SHA256").update(unsigned).sign(PRIVATE_KEY, "base64url");
  return `${unsigned}.${signature}`;
}
async function github(path, options = {}) {
  const response = await fetch(`${GITHUB}${path}`, {
    ...options,
    headers: { accept: "application/vnd.github+json", "x-github-api-version": "2026-03-10", ...(options.headers || {}) },
  });
  const text = await response.text();
  let data; try { data = JSON.parse(text); } catch { data = { message: text }; }
  if (!response.ok) throw Object.assign(new Error(data.message || "GitHub request failed"), { status: response.status, data });
  return data;
}
async function installationToken(installationId) {
  const data = await github(`/app/installations/${installationId}/access_tokens`, {
    method: "POST", headers: { authorization: `Bearer ${jwt()}`, "content-type": "application/json" }, body: "{}",
  });
  return data;
}
async function handle(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (req.method === "GET" && url.pathname === "/healthz") return json(res, 200, { ok: true });
  if (req.method === "POST" && url.pathname === "/v1/github/install/start") {
    const input = await body(req);
    if (!input.state || input.redirect_uri !== PUBLIC_REDIRECT) return json(res, 400, { error: "invalid_start" });
    const brokerState = random();
    transactions.set(brokerState, { appState: input.state, redirectUri: input.redirect_uri, expiresAt: Date.now() + 300000 });
    const authorizationUrl = `https://github.com/apps/${encodeURIComponent(APP_SLUG)}/installations/new?state=${encodeURIComponent(brokerState)}`;
    return json(res, 200, { authorization_url: authorizationUrl });
  }
  if (req.method === "GET" && url.pathname === "/v1/github/install/callback") {
    const transaction = transactions.get(url.searchParams.get("state"));
    if (!transaction || transaction.expiresAt < Date.now()) return json(res, 400, { error: "invalid_install_state" });
    const installationId = Number(url.searchParams.get("installation_id"));
    if (!Number.isSafeInteger(installationId) || url.searchParams.get("setup_action") === "cancel") return json(res, 400, { error: "installation_cancelled" });
    transactions.delete(url.searchParams.get("state"));
    const ticket = random();
    tickets.set(ticket, { ...transaction, installationId, expiresAt: Date.now() + 120000 });
    const redirect = new URL(transaction.redirectUri);
    redirect.searchParams.set("state", transaction.appState);
    redirect.searchParams.set("broker_ticket", ticket);
    res.writeHead(303, { location: redirect.toString(), "cache-control": "no-store" }); res.end(); return;
  }
  if (req.method === "POST" && url.pathname === "/v1/github/install/complete") {
    const input = await body(req); const ticket = tickets.get(input.ticket);
    if (!ticket || ticket.expiresAt < Date.now()) return json(res, 400, { error: "invalid_ticket" });
    tickets.delete(input.ticket);
    const token = await installationToken(ticket.installationId);
    const sessionToken = random();
    sessions.set(sessionToken, { installationId: ticket.installationId, token: token.token, expiresAt: Date.parse(token.expires_at), repositories: token.repositories || [], permissions: token.permissions || {} });
    return json(res, 200, { session_token: sessionToken, installation_id: ticket.installationId, expires_at: token.expires_at, repositories: token.repositories || [], permissions: token.permissions || {} });
  }
  const sessionToken = (req.headers.authorization || "").replace(/^Bearer\s+/i, "");
  const session = sessions.get(sessionToken);
  if (url.pathname.startsWith("/v1/github/session/") || url.pathname === "/v1/github/execute") {
    if (!session || session.expiresAt <= Date.now()) return json(res, 401, { error: "session_expired" });
    if (req.method === "GET" && url.pathname === "/v1/github/session/health") {
      const user = await github("/installation", { headers: { authorization: `Bearer ${session.token}` } });
      return json(res, 200, { login: user.account?.login || user.account?.slug || "authorized", installation_id: session.installationId, expires_at: new Date(session.expiresAt).toISOString() });
    }
    if (req.method === "POST" && url.pathname === "/v1/github/execute") {
      const input = await body(req); const result = await execute(input, session.token);
      return json(res, 200, result);
    }
  }
  return json(res, 404, { error: "not_found" });
}
async function execute(input, token) {
  const p = input.params || {}; const repo = String(p.repo || "");
  const headers = { authorization: `Bearer ${token}` };
  switch (input.action) {
    case "status": return github("/installation", { headers });
    case "list_repos": return github(`/installation/repositories?per_page=${Math.min(Number(p.limit || 30), 100)}`, { headers });
    case "list_issues": return github(`/repos/${repo}/issues?state=open`, { headers });
    case "search_code": return github(`/search/code?q=${encodeURIComponent(String(p.query || ""))}${repo ? `+repo:${encodeURIComponent(repo)}` : ""}`, { headers });
    case "get_file": return github(`/repos/${repo}/contents/${String(p.path || "")}`, { headers });
    case "list_prs": return github(`/repos/${repo}/pulls?state=open`, { headers });
    case "create_issue": return github(`/repos/${repo}/issues`, { method: "POST", headers: { ...headers, "content-type": "application/json" }, body: JSON.stringify({ title: p.title, body: input.text || p.body || "" }) });
    default: throw Object.assign(new Error("action_not_allowed"), { status: 403 });
  }
}
http.createServer((req, res) => handle(req, res).catch(error => json(res, error.status || 500, { error: error.message }))).listen(PORT, "0.0.0.0", () => console.log(`GitHub Broker listening on ${PORT}`));
