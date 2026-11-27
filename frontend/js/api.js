const KEYS = {
  deviceId: "wrongbook.web.deviceId",
  token: "wrongbook.web.token",
  profile: "wrongbook.web.profile",
  apiBase: "wrongbook.web.apiBase"
};

function defaultApiBase() {
  const saved = localStorage.getItem(KEYS.apiBase);
  if (saved) return saved.replace(/\/+$/, "");
  if (window.location.protocol === "http:" || window.location.protocol === "https:") {
    return window.location.origin + "/api/v1";
  }
  return "http://localhost:8080/api/v1";
}

function createDeviceId() {
  return (
    "web-" +
    Date.now().toString(36) +
    "-" +
    Math.random().toString(36).slice(2) +
    "-" +
    Math.random().toString(36).slice(2)
  );
}

export const authState = {
  token: localStorage.getItem(KEYS.token) || "",
  profile: (() => {
    try {
      return JSON.parse(localStorage.getItem(KEYS.profile) || "null");
    } catch {
      return null;
    }
  })(),
  readyPromise: null
};

export function getApiBase() {
  return defaultApiBase();
}

export function setApiBase(url) {
  const clean = String(url || "").trim().replace(/\/+$/, "");
  if (!clean) {
    localStorage.removeItem(KEYS.apiBase);
  } else {
    localStorage.setItem(KEYS.apiBase, clean);
  }
}

export function getDeviceId() {
  let id = localStorage.getItem(KEYS.deviceId);
  if (!id) {
    id = createDeviceId();
    localStorage.setItem(KEYS.deviceId, id);
  }
  return id;
}

export async function loginForDevelopment(customDeviceId) {
  const deviceId = customDeviceId ? String(customDeviceId).trim() : getDeviceId();
  localStorage.setItem(KEYS.deviceId, deviceId);
  const res = await fetch(getApiBase() + "/auth/dev-login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ deviceId, nickname: "网页端学生" })
  });
  let data = {};
  try {
    data = await res.json();
  } catch {}
  if (!res.ok) {
    throw new Error(data.message || `登录失败（${res.status}）`);
  }
  authState.token = data.token;
  authState.profile = data.profile;
  localStorage.setItem(KEYS.token, data.token);
  localStorage.setItem(KEYS.profile, JSON.stringify(data.profile || null));
  return data;
}

export function ensureReady() {
  if (!authState.readyPromise) {
    authState.readyPromise = loginForDevelopment().catch((err) => {
      authState.readyPromise = null;
      throw err;
    });
  }
  return authState.readyPromise;
}

export function refreshSession() {
  authState.readyPromise = loginForDevelopment().catch((err) => {
    authState.readyPromise = null;
    throw err;
  });
  return authState.readyPromise;
}

export function clearPrivateLocalData() {
  Object.keys(localStorage).forEach((key) => {
    if (key.startsWith("wrongbook.web.") && key !== KEYS.apiBase) {
      localStorage.removeItem(key);
    }
  });
  authState.token = "";
  authState.profile = null;
  authState.readyPromise = null;
}

export async function switchAccount(targetDeviceId) {
  clearPrivateLocalData();
  const nextId = targetDeviceId ? String(targetDeviceId).trim() : createDeviceId();
  authState.readyPromise = loginForDevelopment(nextId);
  return authState.readyPromise;
}

export function queryString(params) {
  const sp = new URLSearchParams();
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") {
      sp.set(k, String(v));
    }
  });
  const str = sp.toString();
  return str ? "?" + str : "";
}

export async function request(path, options = {}, retried = false) {
  await ensureReady();
  const method = options.method || "GET";
  const headers = Object.assign(
    { Authorization: "Bearer " + authState.token },
    options.data !== undefined ? { "Content-Type": "application/json" } : {},
    options.headers || {}
  );
  const res = await fetch(getApiBase() + path, {
    method,
    headers,
    body: options.data !== undefined ? JSON.stringify(options.data) : undefined
  });
  if (res.status === 401 && !retried) {
    await refreshSession();
    return request(path, options, true);
  }
  let data = null;
  const text = await res.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (!res.ok) {
    const err = new Error((data && data.message) || `请求失败（${res.status}）`);
    err.statusCode = res.status;
    throw err;
  }
  return data;
}

export async function uploadFile(path, file, fieldName = "file", requestId = "", retried = false) {
  await ensureReady();
  const formData = new FormData();
  formData.append(fieldName, file);
  const headers = { Authorization: "Bearer " + authState.token };
  if (requestId) headers["X-Upload-Request-ID"] = requestId;
  const res = await fetch(getApiBase() + path, {
    method: "POST",
    headers,
    body: formData
  });
  if (res.status === 401 && !retried) {
    await refreshSession();
    return uploadFile(path, file, fieldName, requestId, true);
  }
  let data = {};
  try {
    data = await res.json();
  } catch {}
  if (!res.ok) {
    const err = new Error(data.message || `上传失败（${res.status}）`);
    err.statusCode = res.status;
    throw err;
  }
  return data;
}

export async function fetchAuthBlobUrl(fileUrl, retried = false) {
  await ensureReady();
  const origin = getApiBase().replace(/\/api\/v1\/?$/, "");
  const url = /^https?:\/\//i.test(fileUrl) ? fileUrl : origin + fileUrl;
  const res = await fetch(url, {
    headers: { Authorization: "Bearer " + authState.token }
  });
  if (res.status === 401 && !retried) {
    await refreshSession();
    return fetchAuthBlobUrl(fileUrl, true);
  }
  if (!res.ok) throw new Error("图片加载失败");
  const blob = await res.blob();
  return URL.createObjectURL(blob);
}

function modeSuffix(mode) {
  const key = String(mode || "QUESTIONS_ONLY").toUpperCase();
  if (key === "QUESTIONS_ONLY") return "学生做题版";
  if (key === "ANSWERS_ONLY") return "纯答案版";
  return "老师做题版";
}

export function safeDocxName(title, mode) {
  const cleanTitle =
    String(title || "历史错题专项卷")
      .replace(/[\\/:*?"<>|\r\n]+/g, "_")
      .trim() || "历史错题专项卷";
  return `${cleanTitle}-${modeSuffix(mode)}.docx`;
}

export function triggerBlobDownload(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

export async function downloadSavedPaper(paperId, mode, title, customTitle = "", retried = false) {
  await ensureReady();
  const cleanCustom = String(customTitle || "").trim();
  const effectiveTitle = cleanCustom || title;
  const fileName = safeDocxName(effectiveTitle, mode);
  const payload = { mode: mode || "" };
  if (cleanCustom) payload.customTitle = cleanCustom;
  const res = await fetch(`${getApiBase()}/practice-papers/${paperId}/export`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + authState.token
    },
    body: JSON.stringify(payload)
  });
  if (res.status === 401 && !retried) {
    await refreshSession();
    return downloadSavedPaper(paperId, mode, title, customTitle, true);
  }
  if (!res.ok) {
    throw new Error(`试卷导出失败（${res.status}）`);
  }
  const blob = await res.blob();
  triggerBlobDownload(blob, fileName);
  return { fileName };
}

export async function checkHealth() {
  try {
    const res = await fetch(getApiBase() + "/health");
    return res.ok;
  } catch {
    return false;
  }
}
