import { request, queryString, downloadSavedPaper } from "../api.js";
import { escapeHtml, formatDateTime, toast, confirmDialog, promptDialog, openModal } from "../ui.js";
import { icon } from "../icons.js";

const MODE_META = {
  QUESTIONS_ONLY: { key: "QUESTIONS_ONLY", label: "学生做题版", desc: "仅含纯题目与作答区，适合打印给学生做题" },
  WITH_ANSWERS: { key: "WITH_ANSWERS", label: "老师做题版", desc: "前半部分纯题卷 + 分页后附完整答案与解析" },
  ANSWERS_ONLY: { key: "ANSWERS_ONLY", label: "纯答案版", desc: "仅含答案速查与解析，节约纸张方便对答案" }
};
const ALL_MODES = [MODE_META.QUESTIONS_ONLY, MODE_META.WITH_ANSWERS, MODE_META.ANSWERS_ONLY];

export async function renderPapers(container, ctx) {
  ctx.setHeader("组卷中心", "管理历史专项组卷记录，随时按需实时复原并导出多版本 Word 试卷", `
    <a class="btn btn-primary btn-sm" href="#/questions">${icon("plus")} 前往错题库勾选组卷</a>
  `);

  let keyword = "";
  let items = [];

  container.innerHTML = `
    <div class="stack-md">
      <div class="card row-between">
        <div style="flex:1;min-width:240px;display:flex;gap:10px;">
          <input class="input input-pill" id="paper-search" placeholder="按试卷名称搜索组卷记录…" />
          <button class="btn btn-primary btn-sm" id="paper-search-btn" type="button">${icon("search")} 搜索</button>
        </div>
        <span style="font-size:12.5px;color:var(--text-muted);">点击任意版本标签即可一键导出 .docx</span>
      </div>
      <div id="papers-list-wrap"></div>
    </div>
  `;

  const listWrap = container.querySelector("#papers-list-wrap");

  function openRestoreModal(paper) {
    let chosenMode = paper.defaultMode || "QUESTIONS_ONLY";
    openModal({
      title: "切换版本 / 改名导出 Word",
      confirmText: "立即导出 .docx",
      bodyHtml: `
        <div class="stack-md" style="margin-top:12px;">
          <div class="field-group">
            <label class="field-label">本次导出文件名（不修改原记录名称）</label>
            <input class="input" id="restore-filename" value="${escapeHtml(paper.title || "")}" />
          </div>
          <div class="field-group">
            <label class="field-label">选择导出版本</label>
            <div class="stack-sm" id="restore-mode-list">
              ${ALL_MODES.map(
                (m) => `
                <button type="button" class="mode-card ${m.key === chosenMode ? "active" : ""}" data-mode="${m.key}">
                  <div class="mode-card-title">${m.label}</div>
                  <div class="mode-card-desc">${m.desc}</div>
                </button>`
              ).join("")}
            </div>
          </div>
        </div>
      `,
      onConfirm: async (modalEl) => {
        const customTitle = modalEl.querySelector("#restore-filename")?.value.trim() || "";
        try {
          await downloadSavedPaper(paper.id, chosenMode, paper.title, customTitle);
          toast("Word 试卷已生成并开始下载", "success");
          load();
          return true;
        } catch (err) {
          toast(err.message || "导出失败", "error");
          return false;
        }
      }
    });
    const listEl = document.getElementById("restore-mode-list");
    listEl?.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-mode]");
      if (!btn) return;
      chosenMode = btn.getAttribute("data-mode");
      listEl.querySelectorAll("[data-mode]").forEach((el) => el.classList.toggle("active", el === btn));
    });
  }

  function renderList() {
    if (!items.length) {
      listWrap.innerHTML = `
        <div class="card empty-state">
          <div class="empty-title">暂无组卷记录</div>
          <p>在「历史错题库」开启勾选组卷并导出后，会自动保存在这里以便反复复原不同版本。</p>
          <a class="btn btn-primary" href="#/questions">前往勾选错题组卷</a>
        </div>
      `;
      return;
    }
    listWrap.innerHTML = `
      <div class="grid-2">
        ${items
          .map((paper) => {
            const used = Array.isArray(paper.usedModes) && paper.usedModes.length
              ? paper.usedModes
              : [paper.defaultMode || "QUESTIONS_ONLY"];
            return `
              <article class="card stack-md">
                <div class="row-between">
                  <div>
                    <h3 class="card-title">${escapeHtml(paper.title || "未命名试卷")}</h3>
                    <p class="card-desc">共 ${paper.questionCount || (paper.questionIds || []).length || 0} 道历史错题 · 更新于 ${formatDateTime(
              paper.updatedAt || paper.createdAt
            )}</p>
                  </div>
                </div>

                <div class="row-gap">
                  ${used
                    .map((mKey) => {
                      const meta = MODE_META[mKey] || MODE_META.QUESTIONS_ONLY;
                      return `<button type="button" class="chip-btn active" data-quick-export="${escapeHtml(paper.id)}" data-mode="${
                        meta.key
                      }" data-title="${escapeHtml(paper.title)}">${icon("download")} ${meta.label}</button>`;
                    })
                    .join("")}
                </div>

                <div class="row-between" style="padding-top:10px;border-top:1px solid var(--border-subtle);">
                  <button type="button" class="btn btn-tonal btn-sm" data-open-restore="${escapeHtml(paper.id)}">切换版本 / 改名导出</button>
                  <div class="row-gap">
                    <button type="button" class="btn btn-ghost btn-sm" data-rename-id="${escapeHtml(paper.id)}" data-title="${escapeHtml(
                      paper.title
                    )}">重命名</button>
                    <button type="button" class="btn btn-ghost btn-sm" style="color:var(--danger);" data-delete-id="${escapeHtml(
                      paper.id
                    )}" data-title="${escapeHtml(paper.title)}">删除</button>
                  </div>
                </div>
              </article>`;
          })
          .join("")}
      </div>
    `;

    listWrap.querySelectorAll("[data-quick-export]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        btn.disabled = true;
        try {
          await downloadSavedPaper(
            btn.getAttribute("data-quick-export"),
            btn.getAttribute("data-mode"),
            btn.getAttribute("data-title")
          );
          toast("试卷已实时复原并开始下载", "success");
        } catch (err) {
          toast(err.message || "导出失败", "error");
        } finally {
          btn.disabled = false;
        }
      });
    });

    listWrap.querySelectorAll("[data-open-restore]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const paper = items.find((it) => it.id === btn.getAttribute("data-open-restore"));
        if (paper) openRestoreModal(paper);
      });
    });

    listWrap.querySelectorAll("[data-rename-id]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const id = btn.getAttribute("data-rename-id");
        const oldTitle = btn.getAttribute("data-title");
        const nextTitle = await promptDialog({ title: "修改试卷记录名称", defaultValue: oldTitle, placeholder: "输入新的试卷名称" });
        if (!nextTitle) return;
        try {
          await request(`/practice-papers/${id}`, { method: "PUT", data: { title: nextTitle } });
          toast("名称已更新", "success");
          load();
        } catch (err) {
          toast(err.message || "修改失败", "error");
        }
      });
    });

    listWrap.querySelectorAll("[data-delete-id]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const id = btn.getAttribute("data-delete-id");
        const title = btn.getAttribute("data-title");
        const ok = await confirmDialog({
          title: "删除组卷记录",
          message: `确定删除「${title}」吗？原错题不会被删除。`,
          confirmText: "删除",
          danger: true
        });
        if (!ok) return;
        try {
          await request(`/practice-papers/${id}`, { method: "DELETE" });
          toast("已删除组卷记录", "success");
          load();
        } catch (err) {
          toast(err.message || "删除失败", "error");
        }
      });
    });
  }

  async function load() {
    listWrap.innerHTML = `<div class="loading-state"><div class="spinner"></div><p>正在加载组卷记录…</p></div>`;
    try {
      items = (await request("/practice-papers" + queryString({ keyword: keyword.trim() }))) || [];
      renderList();
    } catch (err) {
      listWrap.innerHTML = `<div class="card empty-state"><div class="empty-title">加载失败</div><p>${escapeHtml(err.message)}</p></div>`;
    }
  }

  const input = container.querySelector("#paper-search");
  container.querySelector("#paper-search-btn").addEventListener("click", () => {
    keyword = input.value;
    load();
  });
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      keyword = input.value;
      load();
    }
  });

  load();
}
