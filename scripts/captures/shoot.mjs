// Captures connectees via le protocole DevTools de Chromium (theme sombre, graphiques charges).
// Usage : node shoot.mjs <session> <sortie> <largeur> <hauteur> <url> [<url>...]  (sortie : dossier)
import { spawn } from "node:child_process";
import { writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const [session, out, width, height, ...urls] = process.argv.slice(2);
const profile = mkdtempSync(join(process.env.SCRATCH || tmpdir(), "chrome-"));
const port = 9333;
const chrome = spawn("chromium", ["--headless=new", "--no-sandbox", "--hide-scrollbars", `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
  "--force-dark-mode", "about:blank"], { stdio: "ignore" });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let targets;
for (let i = 0; i < 50; i++) {
  try { targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json(); if (targets.some((t) => t.type === "page")) break; } catch { }
  await sleep(200);
}
const page = targets.find((t) => t.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener("open", r));
let id = 0; const pending = new Map();
ws.addEventListener("message", (event) => { const msg = JSON.parse(event.data); if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); } });
const send = (method, params = {}) => new Promise((r) => { const n = ++id; pending.set(n, r); ws.send(JSON.stringify({ id: n, method, params })); });

await send("Page.enable");
await send("Network.enable");
for (const [name, value] of [["patria_session", session], ["theme", "dark"]]) {
  await send("Network.setCookie", { name, value, domain: "localhost", path: "/" });
}
await send("Emulation.setDeviceMetricsOverride", { width: Number(width), height: Number(height), deviceScaleFactor: 2, mobile: Number(width) < 600 });
await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: "dark" }, { name: "prefers-reduced-motion", value: "reduce" }] });
for (const entry of urls) {
  const [url, name, script] = entry.split("|");
  await send("Page.navigate", { url });
  await sleep(4500);
  if (script) { await send("Runtime.evaluate", { expression: script }); await sleep(1500); }
  const shot = await send("Page.captureScreenshot", { format: "png" });
  writeFileSync(join(out, name + ".png"), Buffer.from(shot.result.data, "base64"));
  console.log("ok", name);
}
ws.close(); chrome.kill();
