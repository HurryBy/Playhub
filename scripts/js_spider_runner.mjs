import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { TextDecoder } from "node:util";
import { fileURLToPath, pathToFileURL } from "node:url";

const payload = JSON.parse(fs.readFileSync(0, "utf8") || "{}");
const storageDir = path.resolve(payload.storageDir || ".cache/tvbox/js-local");
fs.mkdirSync(storageDir, { recursive: true });
const debugEnabled = process.env.TVBOX_JS_DEBUG === "1";

function debugLog(...args) {
  if (!debugEnabled) {
    return;
  }
  const line = args
    .map((arg) => (typeof arg === "string" ? arg : JSON.stringify(arg)))
    .join(" ");
  process.stderr.write(`${line}\n`);
}

function stableHash(value) {
  return createHash("md5").update(String(value ?? "")).digest("hex");
}

function stripSearchHash(url) {
  const next = new URL(url);
  next.search = "";
  next.hash = "";
  return next.toString();
}

function pathToImportSpecifier(fromFile, toFile) {
  let relative = path.relative(path.dirname(fromFile), toFile);
  relative = relative.split(path.sep).join("/");
  if (!relative.startsWith(".")) {
    relative = `./${relative}`;
  }
  return relative;
}

function moduleExtension(specifier) {
  const base =
    String(specifier ?? "")
      .split("#")[0]
      .split("?")[0] || "";
  const ext = path.extname(base);
  if (!ext || ext.length > 8) {
    return ".js";
  }
  return ext;
}

function looksLikeCheerioModule(specifier) {
  const target = String(specifier ?? "").toLowerCase();
  return (
    target.includes("/cheerio.min.js") ||
    target.endsWith("/cheerio.js") ||
    /(^|[/\\])cheerio(?:\.min)?\.m?js(?:$|[?#])/.test(target)
  );
}

const RUNNER_DIR = path.dirname(fileURLToPath(import.meta.url));
const BUNDLED_CHEERIO_PATH = path.join(RUNNER_DIR, "vendor", "cheerio.min.js");
let bundledCheerioRuntimePromise = null;

function normalizeCheerioRuntime(candidate) {
  const attempts = [
    candidate,
    candidate?.default,
    candidate?.cheerio,
    candidate?.default?.cheerio,
    candidate?.default?.default,
  ];

  for (const attempt of attempts) {
    if (attempt && typeof attempt.load === "function") {
      return attempt;
    }
  }
  return null;
}

async function loadBundledCheerioRuntime() {
  if (bundledCheerioRuntimePromise) {
    return bundledCheerioRuntimePromise;
  }

  bundledCheerioRuntimePromise = (async () => {
    if (!fs.existsSync(BUNDLED_CHEERIO_PATH)) {
      return null;
    }
    const bundledModule = await import(pathToFileURL(BUNDLED_CHEERIO_PATH).href);
    return normalizeCheerioRuntime(bundledModule);
  })();

  return bundledCheerioRuntimePromise;
}

function decodeFetchBuffer(buffer, headers = {}) {
  const contentType =
    headers["content-type"] ||
    headers["Content-Type"] ||
    "";
  const match = String(contentType).match(/charset=([^;]+)/i);
  const charset = match ? match[1].trim().toLowerCase() : "utf-8";
  try {
    return new TextDecoder(charset).decode(buffer);
  } catch {
    return buffer.toString("utf8");
  }
}

function rewriteLegacyMirrorUrl(url) {
  const source = String(url ?? "");
  const mappings = [
    [
      "https://gitcode.net/qq_32394351/dr_py/-/raw/master/",
      "http://home.jundie.top:666/JS/dr_py/",
    ],
    [
      "https://gitcode.net/qq_32394351/dr_py/-/raw/main/",
      "http://home.jundie.top:666/JS/dr_py/",
    ],
    [
      "https://raw.githubusercontent.com/hjdhnx/dr_py/master/",
      "http://home.jundie.top:666/JS/dr_py/",
    ],
    [
      "https://raw.githubusercontent.com/hjdhnx/dr_py/main/",
      "http://home.jundie.top:666/JS/dr_py/",
    ],
    [
      "https://gh-proxy.org/raw.githubusercontent.com/hjdhnx/dr_py/master/",
      "http://home.jundie.top:666/JS/dr_py/",
    ],
    [
      "https://gh-proxy.org/raw.githubusercontent.com/hjdhnx/dr_py/main/",
      "http://home.jundie.top:666/JS/dr_py/",
    ],
  ];

  for (const [from, to] of mappings) {
    if (source.startsWith(from)) {
      return `${to}${source.slice(from.length)}`;
    }
  }
  return source;
}

function storageFile(scope) {
  return path.join(storageDir, `${encodeURIComponent(scope || "default")}.json`);
}

function readScope(scope) {
  const file = storageFile(scope);
  if (!fs.existsSync(file)) {
    return {};
  }
  try {
    return JSON.parse(fs.readFileSync(file, "utf8") || "{}");
  } catch {
    return {};
  }
}

function writeScope(scope, value) {
  fs.writeFileSync(storageFile(scope), JSON.stringify(value), "utf8");
}

function parseHeaders(raw) {
  const blocks = raw
    .split(/\r?\n\r?\n/)
    .map((block) => block.trim())
    .filter(Boolean);
  const last = blocks[blocks.length - 1] || "";
  const lines = last.split(/\r?\n/).slice(1);
  const headers = {};
  for (const line of lines) {
    const idx = line.indexOf(":");
    if (idx < 0) continue;
    const key = line.slice(0, idx).trim();
    const value = line.slice(idx + 1).trim();
    if (headers[key] === undefined) {
      headers[key] = value;
    } else if (Array.isArray(headers[key])) {
      headers[key].push(value);
    } else {
      headers[key] = [headers[key], value];
    }
  }
  return headers;
}

function joinUrl(parent, child) {
  if (!parent) {
    return child || "";
  }
  try {
    return new URL(child || "", parent).toString();
  } catch {
    return child || parent || "";
  }
}

function isIndex(str) {
  if (!str) return false;
  for (const marker of [":eq", ":lt", ":gt", ":first", ":last", "body", "#"]) {
    if (!str.includes(marker)) continue;
    if (marker === "body" || marker === "#") {
      return str.startsWith(marker);
    }
    return true;
  }
  return false;
}

function isUrlAttr(str) {
  if (!str) return false;
  for (const marker of ["url", "src", "href", "-original", "-play"]) {
    if (str.includes(marker)) return true;
  }
  return false;
}

function parseHikerToJq(parse, first) {
  if (!parse) return "";
  if (parse.includes("&&")) {
    const parts = parse.split("&&");
    const next = [];
    for (let i = 0; i < parts.length; i += 1) {
      const part = parts[i];
      const tokens = part.split(" ");
      const selector = tokens[tokens.length - 1];
      if (!isIndex(selector)) {
        if (!first && i >= parts.length - 1) {
          next.push(part);
        } else {
          next.push(`${part}:eq(0)`);
        }
      } else {
        next.push(part);
      }
    }
    return next.join(" ");
  }
  const tokens = parse.split(" ");
  const selector = tokens[tokens.length - 1];
  if (!isIndex(selector) && first) {
    return `${parse}:eq(0)`;
  }
  return parse;
}

function getParseInfo(nparse) {
  const info = {
    nparse_rule: nparse,
    nparse_index: 0,
    excludes: [],
    hasEq: nparse.includes(":eq"),
  };
  if (info.hasEq) {
    info.nparse_rule = nparse.split(":")[0];
    let nparsePos = nparse.split(":")[1];
    if (info.nparse_rule.includes("--")) {
      const rules = info.nparse_rule.split("--");
      info.excludes = rules.slice(1);
      info.nparse_rule = rules[0];
    } else if (nparsePos.includes("--")) {
      const rules = nparsePos.split("--");
      info.excludes = rules.slice(1);
      nparsePos = rules[0];
    }
    const parsed = Number.parseInt(
      nparsePos.replace("eq(", "").replace(")", ""),
      10,
    );
    info.nparse_index = Number.isNaN(parsed) ? 0 : parsed;
  } else if (info.nparse_rule.includes("--")) {
    const rules = info.nparse_rule.split("--");
    info.excludes = rules.slice(1);
    info.nparse_rule = rules[0];
  }
  return info;
}

function wrapSelection(cheerio, htmlList) {
  const $ = cheerio.load(`<root>${htmlList.join("")}</root>`, {
    decodeEntities: false,
  });
  return { $, selection: $("root").children() };
}

function parseOneRule(cheerio, context, nparse) {
  const info = getParseInfo(nparse);
  const select = (selector) =>
    context.selection ? context.selection.find(selector) : context.$(selector);

  let selection;
  if (info.hasEq) {
    const base = select(info.nparse_rule);
    const index =
      info.nparse_index < 0 ? base.length + info.nparse_index : info.nparse_index;
    selection = base.eq(index);
  } else {
    selection = select(nparse);
  }

  if (info.excludes.length > 0 && selection.length > 0) {
    const htmlList = [];
    selection.each((_, element) => {
      const outer = context.$.html(element) || "";
      const local$ = cheerio.load(outer, { decodeEntities: false });
      for (const exclude of info.excludes) {
        local$(exclude).remove();
      }
      local$
        .root()
        .children()
        .each((__, child) => {
          htmlList.push(local$.html(child));
        });
    });
    return wrapSelection(cheerio, htmlList);
  }

  return { $: context.$, selection };
}

function parseDomForUrl(cheerio, html, rule, addUrl = "") {
  const source = String(html ?? "");
  if (rule === "body&&Text" || rule === "Text") {
    return cheerio.load(source).text();
  }
  if (rule === "body&&Html" || rule === "Html") {
    return cheerio.load(source).html() || "";
  }

  let nextRule = String(rule ?? "");
  let option = "";
  if (nextRule.includes("&&")) {
    const parts = nextRule.split("&&");
    option = parts[parts.length - 1];
    nextRule = parts.slice(0, -1).join("&&");
  }
  nextRule = parseHikerToJq(nextRule, true);
  const parses = nextRule.split(" ").filter(Boolean);
  let context = { $: cheerio.load(source, { decodeEntities: false }), selection: null };
  for (const nparse of parses) {
    context = parseOneRule(cheerio, context, nparse);
    if (!context.selection || context.selection.length === 0) {
      return "";
    }
  }

  let result = "";
  if (option) {
    if (option === "Text") {
      result = context.selection.text();
    } else if (option === "Html") {
      result = context.selection.html() || "";
    } else {
      result = context.selection.attr(option) || "";
      if (/style/i.test(option) && /url\(/.test(result)) {
        const match = result.match(/url\((.*?)\)/);
        if (match) {
          result = match[1];
        }
      }
      if (result && addUrl && isUrlAttr(option)) {
        if (result.includes("http")) {
          result = result.slice(result.indexOf("http"));
        } else {
          result = joinUrl(addUrl, result);
        }
      }
    }
  } else {
    result = context.selection
      .map((_, element) => context.$.html(element))
      .get()
      .join("");
  }
  return result;
}

function parseDomForList(cheerio, html, rule) {
  const source = String(html ?? "");
  const nextRule = parseHikerToJq(String(rule ?? ""), false);
  const parses = nextRule.split(" ").filter(Boolean);
  let context = { $: cheerio.load(source, { decodeEntities: false }), selection: null };
  for (const nparse of parses) {
    context = parseOneRule(cheerio, context, nparse);
    if (!context.selection || context.selection.length === 0) {
      return [];
    }
  }
  return context.selection
    .map((_, element) => context.$.html(element))
    .get();
}

function decodeContent(buffer, headers) {
  const contentType =
    headers["content-type"] ||
    headers["Content-Type"] ||
    "";
  const match = String(contentType).match(/charset=([^;]+)/i);
  const charset = match ? match[1].trim().toLowerCase() : "utf-8";
  try {
    return new TextDecoder(charset).decode(buffer);
  } catch {
    return buffer.toString("utf8");
  }
}

const NODE_FETCH_FALLBACK_SCRIPT = `
import fs from "node:fs";
import { TextDecoder } from "node:util";

const payload = JSON.parse(fs.readFileSync(0, "utf8") || "{}");
const options = payload.options || {};
const method = String(options.method || "GET").toUpperCase();
const headers = options.headers || {};
const body = options.data ?? options.body;
const requestInit = {
  method,
  headers,
  redirect: Number(options.redirect ?? 1) === 1 ? "follow" : "manual",
};

if (body !== undefined && body !== null && method !== "GET" && method !== "HEAD" && method !== "HEADER") {
  requestInit.body = typeof body === "string" ? body : JSON.stringify(body);
}

const response = await fetch(payload.url, requestInit);
const buffer = Buffer.from(await response.arrayBuffer());
const contentType = response.headers.get("content-type") || "";
const charsetMatch = contentType.match(/charset=([^;]+)/i);
const charset = charsetMatch ? charsetMatch[1].trim().toLowerCase() : "utf-8";
let textContent = "";
try {
  textContent = new TextDecoder(charset).decode(buffer);
} catch {
  textContent = buffer.toString("utf8");
}

const responseHeaders = {};
for (const [key, value] of response.headers.entries()) {
  if (responseHeaders[key] === undefined) {
    responseHeaders[key] = value;
  } else if (Array.isArray(responseHeaders[key])) {
    responseHeaders[key].push(value);
  } else {
    responseHeaders[key] = [responseHeaders[key], value];
  }
}
if (typeof response.headers.getSetCookie === "function") {
  const setCookies = response.headers.getSetCookie();
  if (setCookies.length > 0) {
    responseHeaders["set-cookie"] = setCookies;
  }
}

const bufferMode = Number(options.buffer ?? 0);
let content;
if (bufferMode === 1) {
  content = Array.from(buffer.values());
} else if (bufferMode === 2) {
  content = buffer.toString("base64");
} else {
  content = textContent;
}

process.stdout.write(JSON.stringify({
  headers: responseHeaders,
  content,
}));
`;

function requestWithNodeFallback(url, options = {}) {
  const payload = JSON.stringify({ url, options });
  const result = spawnSync(
    process.execPath,
    ["--input-type=module", "-e", NODE_FETCH_FALLBACK_SCRIPT],
    {
      input: payload,
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
    },
  );

  if (result.status !== 0) {
    throw new Error(result.stderr || result.stdout || `node fallback exit ${result.status}`);
  }

  return JSON.parse(result.stdout || "{}");
}

function downloadRemoteText(url) {
  const curlBin = process.platform === "win32" ? "curl.exe" : "curl";
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "tvbox-module-"));
  const headerFile = path.join(tempDir, "headers.txt");
  const args = [
    "-sS",
    "--max-time",
    "30",
    "-L",
    "-D",
    headerFile,
    "-o",
    "-",
    "-H",
    "User-Agent: okhttp/3.15",
    "-H",
    "Accept: */*",
    url,
  ];

  const result = spawnSync(curlBin, args, {
    encoding: null,
    maxBuffer: 32 * 1024 * 1024,
  });

  try {
    if (result.status !== 0) {
      const stderr = Buffer.isBuffer(result.stderr)
        ? result.stderr.toString("utf8")
        : (result.stderr || "");
      const stdout = Buffer.isBuffer(result.stdout)
        ? result.stdout.toString("utf8")
        : (result.stdout || "");
      throw new Error(stderr || stdout || `curl exit ${result.status}`);
    }
    const headers = fs.existsSync(headerFile)
      ? parseHeaders(fs.readFileSync(headerFile, "utf8"))
      : {};
    return decodeContent(result.stdout, headers);
  } finally {
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

const LOCALIZE_VERSION = "compat-20260423c";
const FROM_IMPORT_RE =
  /(^|[;\n])(\s*(?:import|export)\s*[^;]*?from\s*)(["'])([^"']+)\3/gm;
const SIDE_EFFECT_IMPORT_RE =
  /(^|[;\n])(\s*import\s*)(["'])([^"']+)\3/gm;

async function replaceAsync(input, regex, replacer) {
  const matches = [...input.matchAll(regex)];
  if (matches.length === 0) {
    return input;
  }

  const replacements = await Promise.all(
    matches.map((match) =>
      replacer(...match, match.index, match.input, match.groups),
    ),
  );

  let result = "";
  let lastIndex = 0;
  for (let i = 0; i < matches.length; i += 1) {
    const match = matches[i];
    result += input.slice(lastIndex, match.index) + replacements[i];
    lastIndex = match.index + match[0].length;
  }
  result += input.slice(lastIndex);
  return result;
}

function createLocalizationState() {
  const runtimeDir = path.dirname(path.resolve(payload.runtimePath));
  const rootDir = path.join(runtimeDir, `__tvbox_runtime_${LOCALIZE_VERSION}`);
  const sourceDir = path.join(rootDir, "src");
  const moduleDir = path.join(rootDir, "mod");
  fs.mkdirSync(sourceDir, { recursive: true });
  fs.mkdirSync(moduleDir, { recursive: true });
  return {
    rootDir,
    sourceDir,
    moduleDir,
    sourceCache: new Map(),
    moduleCache: new Map(),
    bridgeCache: new Map(),
  };
}

const localizationState = createLocalizationState();

async function fetchRemoteModuleText(url, state) {
  url = rewriteLegacyMirrorUrl(url);
  if (state.sourceCache.has(url)) {
    return state.sourceCache.get(url);
  }

  const cacheFile = path.join(
    state.sourceDir,
    `${stableHash(url)}${moduleExtension(url)}`,
  );
  if (fs.existsSync(cacheFile) && fs.statSync(cacheFile).size > 0) {
    const cached = fs.readFileSync(cacheFile, "utf8");
    state.sourceCache.set(url, cached);
    return cached;
  }

  const text = downloadRemoteText(url);
  fs.writeFileSync(cacheFile, text, "utf8");
  state.sourceCache.set(url, text);
  return text;
}

async function applyLegacyQueryPlaceholders(content, sourceUrl, state) {
  if (!sourceUrl || !String(sourceUrl).includes("?")) {
    return content;
  }

  const url = new URL(sourceUrl);
  if (!url.search) {
    return content;
  }

  let nextContent = content;
  const baseUrl = stripSearchHash(sourceUrl);
  for (const [key, value] of url.searchParams.entries()) {
    if (!key || !value) {
      continue;
    }
    const placeholder = `__${key.toUpperCase()}__`;
    if (!nextContent.includes(placeholder)) {
      continue;
    }
    const resolvedUrl = rewriteLegacyMirrorUrl(
      new URL(value, baseUrl).toString(),
    );
    const rawText = await fetchRemoteModuleText(resolvedUrl, state);
    nextContent = nextContent.split(placeholder).join(rawText);
  }

  return nextContent;
}

function applyLegacyDrpyCompat(content) {
  let nextContent = content;
  const looksLikeLegacyDrpy =
    nextContent.includes("function init(ext)") &&
    nextContent.includes('rule.host=(rule.host||"").rstrip("/");') &&
    nextContent.includes("function detail(vod_url)") &&
    nextContent.includes("function search(wd,quick)");
  if (!looksLikeLegacyDrpy) {
    return nextContent;
  }

  if (!nextContent.includes("globalThis.__TVBOX_HOST__")) {
    nextContent = `var HOST = globalThis.__TVBOX_HOST__ || globalThis.HOST || "";
globalThis.__TVBOX_HOST__ = HOST;
globalThis.HOST = HOST;
${nextContent}`;
  }

  if (
    !nextContent.includes(
      'rule.host=(rule.host||"").rstrip("/");HOST=rule.host||HOST||"";globalThis.__TVBOX_HOST__=HOST;globalThis.HOST=HOST;',
    )
  ) {
    nextContent = nextContent.replace(
      /rule\.host=\(rule\.host\|\|""\)\.rstrip\("\/"\);/g,
      'rule.host=(rule.host||"").rstrip("/");HOST=rule.host||HOST||"";globalThis.__TVBOX_HOST__=HOST;globalThis.HOST=HOST;',
    );
  }

  return nextContent;
}

function adaptLegacySpiderEntry(content) {
  const nextContent = applyLegacyDrpyCompat(content);
  if (
    nextContent.includes("export default") ||
    !nextContent.includes("__JS_SPIDER__")
  ) {
    return nextContent;
  }

  return `${nextContent.replaceAll("__JS_SPIDER__", "globalThis.__TVBOX_SPIDER__")}

const __tvboxSpiderExport = globalThis.__TVBOX_SPIDER__;
export default __tvboxSpiderExport;
`;
}

function ensureCheerioBridge(realModulePath, state) {
  if (state.bridgeCache.has(realModulePath)) {
    return state.bridgeCache.get(realModulePath);
  }

  const bridgePath = path.join(
    state.moduleDir,
    `cheerio-${stableHash(realModulePath)}.mjs`,
  );
  const targetSpecifier = pathToImportSpecifier(bridgePath, realModulePath);
  const bridgeContent = `import * as cheerioNamespace from "${targetSpecifier}";
const cheerioCandidate =
  (cheerioNamespace && typeof cheerioNamespace.load === "function")
    ? cheerioNamespace
    : (cheerioNamespace?.default && typeof cheerioNamespace.default.load === "function")
      ? cheerioNamespace.default
      : globalThis.__tvboxCheerio;
const cheerio = cheerioCandidate;
globalThis.__tvboxCheerio = cheerio;
globalThis.cheerio = cheerio;
export default cheerio;
export * from "${targetSpecifier}";
`;
  fs.writeFileSync(bridgePath, bridgeContent, "utf8");
  state.bridgeCache.set(realModulePath, bridgePath);
  return bridgePath;
}

async function localizeImportSpecifier(specifier, sourceUrl, currentFile, state) {
  if (
    !specifier ||
    specifier.startsWith("node:") ||
    specifier.startsWith("data:") ||
    specifier.startsWith("file:")
  ) {
    return specifier;
  }

  const isRemote = /^https?:\/\//i.test(specifier);
  const isRelative =
    specifier.startsWith("./") ||
    specifier.startsWith("../") ||
    specifier.startsWith("/");

  if (!isRemote && !isRelative) {
    return specifier;
  }

  const resolvedUrl = rewriteLegacyMirrorUrl(
    isRemote ? specifier : new URL(specifier, sourceUrl).toString(),
  );
  let localPath = await localizeDependencyModule(resolvedUrl, state);
  if (looksLikeCheerioModule(resolvedUrl)) {
    localPath = ensureCheerioBridge(localPath, state);
  }
  return pathToImportSpecifier(currentFile, localPath);
}

async function rewriteStaticImports(content, sourceUrl, currentFile, state) {
  const withFromImports = await replaceAsync(
    content,
    FROM_IMPORT_RE,
    async (full, prefix, statement, quote, specifier) => {
      const nextSpecifier = await localizeImportSpecifier(
        specifier,
        sourceUrl,
        currentFile,
        state,
      );
      return `${prefix}${statement}${quote}${nextSpecifier}${quote}`;
    },
  );

  return replaceAsync(
    withFromImports,
    SIDE_EFFECT_IMPORT_RE,
    async (full, prefix, statement, quote, specifier) => {
      const nextSpecifier = await localizeImportSpecifier(
        specifier,
        sourceUrl,
        currentFile,
        state,
      );
      return `${prefix}${statement}${quote}${nextSpecifier}${quote}`;
    },
  );
}

async function localizeDependencyModule(sourceUrl, state) {
  if (state.moduleCache.has(sourceUrl)) {
    return state.moduleCache.get(sourceUrl);
  }

  const targetPath = path.join(
    state.moduleDir,
    `${stableHash(sourceUrl)}.mjs`,
  );
  state.moduleCache.set(sourceUrl, targetPath);

  let content = await fetchRemoteModuleText(sourceUrl, state);
  content = await rewriteStaticImports(content, sourceUrl, targetPath, state);
  fs.writeFileSync(targetPath, content, "utf8");
  return targetPath;
}

async function localizeRuntimeEntry() {
  const runtimePath = path.resolve(payload.runtimePath);
  const runtimeUrl =
    payload.runtimeUrl || pathToFileURL(runtimePath).href;
  const targetPath = path.join(
    localizationState.moduleDir,
    `entry-${stableHash(runtimeUrl)}.mjs`,
  );

  let content = fs.readFileSync(runtimePath, "utf8");
  content = await applyLegacyQueryPlaceholders(
    content,
    runtimeUrl,
    localizationState,
  );
  content = await rewriteStaticImports(
    content,
    runtimeUrl,
    targetPath,
    localizationState,
  );
  content = adaptLegacySpiderEntry(content);
  fs.writeFileSync(targetPath, content, "utf8");
  return targetPath;
}

async function primeCheerioRuntime() {
  const cachedRuntime = normalizeCheerioRuntime(globalThis.__tvboxCheerio);
  if (cachedRuntime) {
    globalThis.__tvboxCheerio = cachedRuntime;
    globalThis.cheerio = cachedRuntime;
    return cachedRuntime;
  }

  const corePath = path.resolve(
    path.dirname(payload.runtimePath),
    "drpy-core-lite.min.js",
  );
  if (fs.existsSync(corePath)) {
    const coreModule = await import(pathToFileURL(corePath).href);
    const cheerio = normalizeCheerioRuntime(coreModule);
    if (cheerio) {
      globalThis.__tvboxCheerio = cheerio;
      globalThis.cheerio = cheerio;
      return cheerio;
    }
  }

  const cheerio = await loadBundledCheerioRuntime();
  if (cheerio) {
    globalThis.__tvboxCheerio = cheerio;
    globalThis.cheerio = cheerio;
  }
  return cheerio;
}

function getCheerioRuntime() {
  const cheerio = normalizeCheerioRuntime(globalThis.__tvboxCheerio);
  if (!cheerio) {
    throw new Error("Cheerio runtime is unavailable for current JS spider");
  }
  globalThis.__tvboxCheerio = cheerio;
  globalThis.cheerio = cheerio;
  return cheerio;
}

globalThis.local = {
  get(scope, key) {
    const data = readScope(scope);
    return data[key] ?? "";
  },
  set(scope, key, value) {
    const data = readScope(scope);
    data[key] = value;
    writeScope(scope, data);
  },
  delete(scope, key) {
    const data = readScope(scope);
    delete data[key];
    writeScope(scope, data);
  },
};

globalThis.getProxyUrl = () => payload.proxyUrl || "";
globalThis.OCR_API = "";
console.log = debugLog;
globalThis.log = debugLog;
globalThis.print = debugLog;

globalThis.req = (url, options = {}) => {
  const curlBin = process.platform === "win32" ? "curl.exe" : "curl";
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "tvbox-js-"));
  const headerFile = path.join(tempDir, "headers.txt");
  const timeoutMs = Number(options.timeout ?? 10000);
  const args = [
    "-sS",
    "--max-time",
    String(Math.max(1, Math.ceil(timeoutMs / 1000))),
    "-D",
    headerFile,
    "-o",
    "-",
  ];
  const method = String(options.method || "GET").toUpperCase();
  if (Number(options.redirect ?? 1) === 1) {
    args.push("-L");
  }
  if (method === "HEAD" || method === "HEADER") {
    args.push("-I");
  } else if (method !== "GET") {
    args.push("-X", method);
  }
  const headers = options.headers || {};
  for (const [key, value] of Object.entries(headers)) {
    args.push("-H", `${key}: ${String(value)}`);
  }
  const body = options.data ?? options.body;
  if (body !== undefined && body !== null && method !== "GET" && method !== "HEAD" && method !== "HEADER") {
    args.push(
      "--data-binary",
      typeof body === "string" ? body : JSON.stringify(body),
    );
  }
  args.push(url);

  const result = spawnSync(curlBin, args, { encoding: null });
  try {
    const responseHeaders = fs.existsSync(headerFile) ? parseHeaders(fs.readFileSync(headerFile, "utf8")) : {};
    if (result.status !== 0) {
      const stderr = Buffer.isBuffer(result.stderr)
        ? result.stderr.toString("utf8")
        : (result.stderr || "");
      const stdout = Buffer.isBuffer(result.stdout)
        ? result.stdout.toString("utf8")
        : (result.stdout || "");
      try {
        return requestWithNodeFallback(url, options);
      } catch (fallbackError) {
        throw new Error(
          `${stderr || stdout || `curl exit ${result.status}`}; fallback=${fallbackError.message}`,
        );
      }
    }
    const bufferMode = Number(options.buffer ?? 0);
    let content;
    if (bufferMode === 1) {
      content = Array.from(result.stdout.values());
    } else if (bufferMode === 2) {
      content = result.stdout.toString("base64");
    } else {
      content = decodeContent(result.stdout, responseHeaders);
    }
    return {
      content,
      headers: responseHeaders,
    };
  } finally {
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
};

globalThis.joinUrl = joinUrl;
globalThis.pdfh = (html, rule) =>
  parseDomForUrl(getCheerioRuntime(), html, String(rule ?? ""), "");
globalThis.pdfa = (html, rule) =>
  parseDomForList(getCheerioRuntime(), html, String(rule ?? ""));
globalThis.pd = (html, rule, baseUrl = "") =>
  parseDomForUrl(
    getCheerioRuntime(),
    html,
    String(rule ?? ""),
    String(baseUrl ?? ""),
  );

await primeCheerioRuntime();
const localizedRuntimePath = await localizeRuntimeEntry();
const runtimeModule = await import(pathToFileURL(localizedRuntimePath).href);
await primeCheerioRuntime();
const spider =
  runtimeModule.default ||
  runtimeModule.__TVBOX_SPIDER__ ||
  globalThis.__TVBOX_SPIDER__ ||
  runtimeModule;
const args = payload.args || {};

if (typeof spider.init === "function") {
  spider.init(payload.ext || "");
}

let result = "{}";
switch (payload.action) {
  case "home":
    result = spider.home(Boolean(args.filter));
    break;
  case "homeVod":
    result =
      typeof spider.homeVod === "function"
        ? spider.homeVod()
        : "{}";
    break;
  case "category":
    result = spider.category(args.tid || "", args.pg || "1", Boolean(args.filter), args.extend || {});
    break;
  case "detail":
    result = spider.detail(args.id || "");
    break;
  case "search":
    result = spider.search(args.wd || "", Boolean(args.quick));
    break;
  case "play":
    result = spider.play(args.flag || "", args.id || "", args.vipFlags || []);
    break;
  default:
    throw new Error(`Unsupported action: ${payload.action}`);
}

if (typeof result === "string") {
  process.stdout.write(result);
} else {
  process.stdout.write(JSON.stringify(result ?? {}));
}
