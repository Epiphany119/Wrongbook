export function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

export function todayString() {
  const date = new Date();
  return (
    date.getFullYear() +
    "-" +
    String(date.getMonth() + 1).padStart(2, "0") +
    "-" +
    String(date.getDate()).padStart(2, "0")
  );
}

export function formatDateTime(value) {
  if (!value) return "—";
  const text = String(value).replace("T", " ").replace(/\.\d+Z?$/, "");
  return text.length > 16 ? text.slice(0, 16) : text;
}

export function stateBadge(state) {
  const map = {
    NEW: { label: "新收录", cls: "pill-blue" },
    REVIEW: { label: "待复习", cls: "pill-amber" },
    MASTERED: { label: "已通过", cls: "pill-green" },
    ARCHIVED: { label: "已归档", cls: "pill-gray" }
  };
  return map[state] || { label: "待复习", cls: "pill-amber" };
}

export function toast(message, type = "default") {
  const container = document.getElementById("toast-container");
  if (!container) return;
  const el = document.createElement("div");
  el.className = "toast" + (type !== "default" ? " " + type : "");
  el.textContent = message;
  container.appendChild(el);
  setTimeout(() => {
    el.style.opacity = "0";
    el.style.transition = "opacity 0.2s ease";
    setTimeout(() => el.remove(), 220);
  }, 2600);
}

export function closeModal() {
  const root = document.getElementById("modal-root");
  if (root) root.innerHTML = "";
}

export function openModal({ title, bodyHtml, confirmText = "确认", cancelText = "取消", danger = false, onConfirm, showCancel = true }) {
  const root = document.getElementById("modal-root");
  if (!root) return;
  root.innerHTML = `
    <div class="modal-backdrop" id="active-modal-backdrop">
      <div class="modal-card" role="dialog" aria-modal="true">
        <h3 class="modal-title">${escapeHtml(title)}</h3>
        <div class="modal-body">${bodyHtml}</div>
        <div class="modal-actions">
          ${showCancel ? `<button type="button" class="btn btn-secondary" id="modal-cancel-btn">${escapeHtml(cancelText)}</button>` : ""}
          ${confirmText ? `<button type="button" class="btn ${danger ? "btn-danger" : "btn-primary"}" id="modal-confirm-btn">${escapeHtml(confirmText)}</button>` : ""}
        </div>
      </div>
    </div>
  `;
  const backdrop = document.getElementById("active-modal-backdrop");
  backdrop.addEventListener("click", (e) => {
    if (e.target === backdrop) closeModal();
  });
  const cancelBtn = document.getElementById("modal-cancel-btn");
  if (cancelBtn) cancelBtn.addEventListener("click", closeModal);
  const confirmBtn = document.getElementById("modal-confirm-btn");
  if (confirmBtn) {
    confirmBtn.addEventListener("click", async () => {
      if (!onConfirm) {
        closeModal();
        return;
      }
      confirmBtn.disabled = true;
      try {
        const shouldClose = await onConfirm(backdrop);
        if (shouldClose !== false) closeModal();
      } finally {
        if (document.body.contains(confirmBtn)) confirmBtn.disabled = false;
      }
    });
  }
}

export function confirmDialog({ title, message, confirmText = "确认", danger = false }) {
  return new Promise((resolve) => {
    openModal({
      title,
      bodyHtml: `<p style="margin:8px 0 0;color:var(--text-secondary);line-height:1.65;">${escapeHtml(message)}</p>`,
      confirmText,
      danger,
      onConfirm: () => {
        resolve(true);
        return true;
      }
    });
    const cancelBtn = document.getElementById("modal-cancel-btn");
    const backdrop = document.getElementById("active-modal-backdrop");
    if (cancelBtn) cancelBtn.addEventListener("click", () => resolve(false), { once: true });
    if (backdrop) {
      backdrop.addEventListener("click", (e) => {
        if (e.target === backdrop) resolve(false);
      });
    }
  });
}

export function promptDialog({ title, placeholder = "", defaultValue = "", confirmText = "保存" }) {
  return new Promise((resolve) => {
    openModal({
      title,
      bodyHtml: `<div style="margin-top:12px;"><input class="input" id="modal-prompt-input" value="${escapeHtml(defaultValue)}" placeholder="${escapeHtml(placeholder)}" /></div>`,
      confirmText,
      onConfirm: () => {
        const val = document.getElementById("modal-prompt-input")?.value ?? "";
        resolve(val.trim());
        return true;
      }
    });
    const input = document.getElementById("modal-prompt-input");
    if (input) {
      input.focus();
      input.select();
    }
    const cancelBtn = document.getElementById("modal-cancel-btn");
    if (cancelBtn) cancelBtn.addEventListener("click", () => resolve(null), { once: true });
  });
}

export function openImageLightbox(src) {
  const root = document.getElementById("modal-root");
  if (!root || !src) return;
  root.innerHTML = `
    <div class="modal-backdrop" id="lightbox-backdrop">
      <img class="lightbox-img" src="${escapeHtml(src)}" alt="图片预览" />
    </div>
  `;
  document.getElementById("lightbox-backdrop").addEventListener("click", closeModal);
}
