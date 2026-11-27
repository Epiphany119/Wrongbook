import { ensureReady, checkHealth, fetchAuthBlobUrl, authState } from "./api.js";
import { hydrateIcons } from "./icons.js";
import { renderHome } from "./views/home.js";
import { renderQuestions } from "./views/questions.js";
import { renderQuestionEdit } from "./views/question-edit.js";
import { renderQuestionDetail } from "./views/question-detail.js";
import { renderPractice } from "./views/practice.js";
import { renderPapers } from "./views/papers.js";
import { renderProfile } from "./views/profile.js";
import { renderTrash } from "./views/trash.js";
import { renderFeedback } from "./views/feedback.js";

const viewRoot = document.getElementById("view-root");
const pageTitleEl = document.getElementById("page-title");
const pageSubtitleEl = document.getElementById("page-subtitle");
const topbarActionsEl = document.getElementById("topbar-actions");
const sidebar = document.getElementById("sidebar");
const sidebarBackdrop = document.getElementById("sidebar-backdrop");

function closeMobileSidebar() {
  sidebar.classList.remove("open");
  sidebarBackdrop.classList.remove("open");
}

document.getElementById("btn-menu-toggle")?.addEventListener("click", () => {
  sidebar.classList.toggle("open");
  sidebarBackdrop.classList.toggle("open");
});
sidebarBackdrop?.addEventListener("click", closeMobileSidebar);
document.getElementById("btn-quick-create")?.addEventListener("click", () => {
  closeMobileSidebar();
  window.location.hash = "#/question/new";
});

function setHeader(title, subtitle = "", actionsHtml = "") {
  pageTitleEl.textContent = title;
  pageSubtitleEl.textContent = subtitle;
  topbarActionsEl.innerHTML = actionsHtml;
  document.title = `${title} · 小 E 错题本`;
}

function setActiveNav(routeKey) {
  document.querySelectorAll("[data-route]").forEach((el) => {
    el.classList.toggle("active", el.getAttribute("data-route") === routeKey);
  });
}

async function updateSidebarProfile(profile, preloadedAvatarUrl = "") {
  if (!profile) return;
  const nick = profile.nickname || "网页端学生";
  document.getElementById("sidebar-nickname").textContent = nick;
  const sub = [profile.grade, profile.textbookVersion].filter(Boolean).join(" · ") || "历史专项学习";
  document.getElementById("sidebar-grade").textContent = sub;

  const avatarEl = document.getElementById("sidebar-avatar");
  if (preloadedAvatarUrl) {
    avatarEl.innerHTML = `<img src="${preloadedAvatarUrl}" alt="头像" />`;
    return;
  }
  if (profile.avatarUrl) {
    try {
      const blobUrl = await fetchAuthBlobUrl(profile.avatarUrl);
      avatarEl.innerHTML = `<img src="${blobUrl}" alt="头像" />`;
      return;
    } catch {}
  }
  avatarEl.textContent = nick.charAt(0);
}

const ctx = {
  setHeader,
  updateSidebarProfile
};

async function updateHealthDot() {
  const dot = document.getElementById("backend-status-dot");
  if (!dot) return;
  const ok = await checkHealth();
  dot.classList.toggle("online", ok);
  dot.classList.toggle("offline", !ok);
  dot.title = ok ? "后端已连接" : "无法连接后端服务";
}

function parseHash() {
  const raw = (window.location.hash || "#/home").replace(/^#\/?/, "");
  const [path] = raw.split("?");
  return path.replace(/\/+$/, "") || "home";
}

async function route() {
  closeMobileSidebar();
  const path = parseHash();

  if (path === "home") {
    setActiveNav("home");
    await renderHome(viewRoot, ctx);
  } else if (path === "questions") {
    setActiveNav("questions");
    await renderQuestions(viewRoot, ctx);
  } else if (path === "question/new") {
    setActiveNav("questions");
    await renderQuestionEdit(viewRoot, ctx, "");
  } else if (/^question\/[^/]+\/edit$/.test(path)) {
    const id = decodeURIComponent(path.split("/")[1]);
    setActiveNav("questions");
    await renderQuestionEdit(viewRoot, ctx, id);
  } else if (/^question\/[^/]+$/.test(path)) {
    const id = decodeURIComponent(path.split("/")[1]);
    setActiveNav("questions");
    await renderQuestionDetail(viewRoot, ctx, id);
  } else if (path === "practice") {
    setActiveNav("practice");
    await renderPractice(viewRoot, ctx);
  } else if (path === "papers") {
    setActiveNav("papers");
    await renderPapers(viewRoot, ctx);
  } else if (path === "trash") {
    setActiveNav("trash");
    await renderTrash(viewRoot, ctx);
  } else if (path === "feedback") {
    setActiveNav("feedback");
    renderFeedback(viewRoot, ctx);
  } else if (path === "profile") {
    setActiveNav("profile");
    await renderProfile(viewRoot, ctx);
  } else {
    window.location.hash = "#/home";
  }
}

async function bootstrap() {
  hydrateIcons(document);
  updateHealthDot();
  try {
    await ensureReady();
    if (authState.profile) updateSidebarProfile(authState.profile);
  } catch {}
  await route();
}

window.addEventListener("hashchange", route);
bootstrap();
