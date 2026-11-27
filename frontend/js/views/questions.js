import { request, queryString, downloadSavedPaper } from "../api.js";
import { toPlainText } from "../markdown.js";
import { escapeHtml, stateBadge, todayString, toast, confirmDialog, openModal } from "../ui.js";
import { icon } from "../icons.js";

const STATES = [
  { key: "", label: "未归档" },
  { key: "NEW", label: "新收录" },
  { key: "REVIEW", label: "待复习" },
  { key: "MASTERED", label: "已通过" },
  { key: "ARCHIVED", label: "已归档" }
];

const REASONS = ["", "概念不清", "审题失误", "计算错误", "步骤遗漏", "方法不熟", "其他"];

const PAPER_MODES = [
  { key: "QUESTIONS_ONLY", title: "学生做题版", desc: "仅含纯题目与作答区，适合打印给学生做题" },
  { key: "WITH_ANSWERS", title: "老师做题版", desc: "前半部分纯题卷 + 分页后附完整答案与解析" },
  { key: "ANSWERS_ONLY", title: "纯答案版", desc: "仅含答案速查与解析，节约纸张方便对答案" }
];

export async function renderQuestions(container, ctx) {
  const state = {
    items: [],
    total: 0,
    page: 1,
    pageSize: 20,
    keyword: "",
    status: "",
    chapterId: "",
    errorReason: "",
    tag: "",
    dueOnly: false,
    dateFrom: "",
    dateTo: "",
    sort: "NEWEST",
    chapters: [],
    paperMode: false,
    selectedIds: new Set(),
    showFilters: false,
    loading: false
  };

  function updateHeader() {
    ctx.setHeader(
      "历史错题库",
      `共 ${state.total} 道${state.status === "ARCHIVED" ? "已归档" : "符合条件的"}历史错题`,
      `
        <button class="btn ${state.paperMode ? "btn-primary" : "btn-secondary"} btn-sm" id="top-toggle-paper" type="button">
          ${icon("fileText")} ${state.paperMode ? "退出组卷模式" : "勾选组卷"}
        </button>
        <a class="btn btn-secondary btn-sm" href="#/papers">组卷记录</a>
        <a class="btn btn-primary btn-sm" href="#/question/new">${icon("plus")} 录入错题</a>
      `
    );
    document.getElementById("top-toggle-paper")?.addEventListener("click", () => {
      state.paperMode = !state.paperMode;
      state.selectedIds.clear();
      updateHeader();
      renderListOnly();
    });
  }

  container.innerHTML = `
    <div class="stack-md">
      <div class="card stack-md">
        <div class="row-between">
          <div style="flex:1;min-width:240px;display:flex;gap:10px;">
            <input class="input input-pill" id="q-keyword" placeholder="搜索题干关键词、材料或知识点…" />
            <button class="btn btn-primary btn-sm" id="q-search-btn" type="button">${icon("search")} 搜索</button>
          </div>
          <div class="row-gap">
            <select class="select" id="q-sort" style="width:auto;border-radius:999px;padding:7px 14px;">
              <option value="NEWEST">最新收录</option>
              <option value="DUE_SOON">近期到期</option>
              <option value="MOST_WRONG">最常错</option>
              <option value="RELEVANCE">搜索匹配度</option>
            </select>
            <button class="btn btn-secondary btn-sm" id="q-filter-toggle" type="button">高级筛选</button>
          </div>
        </div>

        <div class="row-between">
          <div class="row-gap" id="q-state-pills">
            ${STATES.map(
              (s) =>
                `<button type="button" class="chip-btn ${s.key === "" ? "active" : ""}" data-state="${s.key}">${s.label}</button>`
            ).join("")}
          </div>
          <label class="row-gap" style="font-size:13px;color:var(--text-secondary);cursor:pointer;">
            <input type="checkbox" id="q-due-only" />
            <span>仅看已到期</span>
          </label>
        </div>

        <div id="q-adv-panel" style="display:none;padding-top:12px;border-top:1px solid var(--border-subtle);">
          <div class="grid-4">
            <div class="field-group">
              <label class="field-label">历史章节</label>
              <select class="select" id="q-chapter"><option value="">全部章节</option></select>
            </div>
            <div class="field-group">
              <label class="field-label">错因分类</label>
              <select class="select" id="q-reason">
                ${REASONS.map((r) => `<option value="${r}">${r || "全部错因"}</option>`).join("")}
              </select>
            </div>
            <div class="field-group">
              <label class="field-label">自定义标签</label>
              <input class="input" id="q-tag" placeholder="如：材料题、辛亥革命" />
            </div>
            <div class="field-group">
              <label class="field-label">收录日期范围</label>
              <div style="display:flex;gap:6px;align-items:center;">
                <input class="input" id="q-date-from" type="date" style="padding:8px 10px;" />
                <span>~</span>
                <input class="input" id="q-date-to" type="date" style="padding:8px 10px;" />
              </div>
            </div>
          </div>
          <div class="row-between" style="margin-top:12px;">
            <span style="font-size:12px;color:var(--text-muted);">筛选条件实时生效</span>
            <button class="btn btn-ghost btn-sm" id="q-reset-filters" type="button">重置全部筛选</button>
          </div>
        </div>
      </div>

      <div id="q-list-wrap"></div>
      <div id="q-floating-wrap"></div>
    </div>
  `;

  const listWrap = container.querySelector("#q-list-wrap");
  const floatingWrap = container.querySelector("#q-floating-wrap");

  try {
    state.chapters = (await request("/taxonomy/chapters" + queryString({ subjectId: "history" }))) || [];
    const chSelect = container.querySelector("#q-chapter");
    chSelect.innerHTML =
      `<option value="">全部章节</option>` +
      state.chapters.map((c) => `<option value="${escapeHtml(c.id)}">${escapeHtml(c.name)}</option>`).join("");
  } catch {}

  function openExportModal() {
    if (!state.selectedIds.size) {
      toast("请先勾选至少一道历史错题", "error");
      return;
    }
    let chosenMode = "QUESTIONS_ONLY";
    const defaultTitle = `历史错题专项卷 ${todayString().slice(5)}`;
    openModal({
      title: `生成 Word 专项试卷（已选 ${state.selectedIds.size} 题）`,
      confirmText: "保存记录并导出 .docx",
      bodyHtml: `
        <div class="stack-md" style="margin-top:14px;">
          <div class="field-group">
            <label class="field-label">试卷名称</label>
            <input class="input" id="export-paper-title" value="${escapeHtml(defaultTitle)}" placeholder="输入试卷名称" />
          </div>
          <div class="field-group">
            <label class="field-label">导出版本</label>
            <div class="stack-sm" id="export-mode-list">
              ${PAPER_MODES.map(
                (m) => `
                <button type="button" class="mode-card ${m.key === chosenMode ? "active" : ""}" data-mode="${m.key}">
                  <div class="mode-card-title">${m.title}</div>
                  <div class="mode-card-desc">${m.desc}</div>
                </button>`
              ).join("")}
            </div>
          </div>
        </div>
      `,
      onConfirm: async (modalEl) => {
        const title = modalEl.querySelector("#export-paper-title")?.value.trim() || defaultTitle;
        const ids = Array.from(state.selectedIds);
        try {
          const saved = await request("/practice-papers", {
            method: "POST",
            data: { title, questionIds: ids, mode: chosenMode }
          });
          await downloadSavedPaper(saved.id, chosenMode, saved.title, title);
          toast("组卷记录已保存，Word 试卷已开始下载", "success");
          state.paperMode = false;
          state.selectedIds.clear();
          updateHeader();
          renderListOnly();
          return true;
        } catch (err) {
          toast(err.message || "导出失败", "error");
          return false;
        }
      }
    });
    const modeList = document.getElementById("export-mode-list");
    modeList?.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-mode]");
      if (!btn) return;
      chosenMode = btn.getAttribute("data-mode");
      modeList.querySelectorAll("[data-mode]").forEach((el) => el.classList.toggle("active", el === btn));
    });
  }

  function renderFloatingBar() {
    if (!state.paperMode) {
      floatingWrap.innerHTML = "";
      return;
    }
    const allSelected = state.items.length > 0 && state.selectedIds.size === state.items.length;
    floatingWrap.innerHTML = `
      <div class="floating-bar">
        <div class="row-gap">
          <span class="pill pill-blue">组卷模式</span>
          <strong>已勾选 ${state.selectedIds.size} 道历史错题</strong>
        </div>
        <div class="row-gap">
          <button class="btn btn-secondary btn-sm" id="bar-select-all" type="button">${allSelected ? "取消全选" : "全选当前列表"}</button>
          <button class="btn btn-primary btn-sm" id="bar-export-btn" type="button" ${state.selectedIds.size ? "" : "disabled"}>
            ${icon("download")} 生成并导出 Word
          </button>
        </div>
      </div>
    `;
    floatingWrap.querySelector("#bar-select-all")?.addEventListener("click", () => {
      if (allSelected) state.selectedIds.clear();
      else state.items.forEach((it) => state.selectedIds.add(it.id));
      renderListOnly();
    });
    floatingWrap.querySelector("#bar-export-btn")?.addEventListener("click", openExportModal);
  }

  function renderListOnly() {
    if (state.loading && state.items.length === 0) {
      listWrap.innerHTML = `<div class="loading-state"><div class="spinner"></div><p>正在加载错题列表…</p></div>`;
      renderFloatingBar();
      return;
    }
    if (!state.items.length) {
      listWrap.innerHTML = `
        <div class="card empty-state">
          <div class="empty-title">没有找到符合条件的历史错题</div>
          <p>可以调整筛选条件，或直接点击右上角录入新的历史错题。</p>
          <a class="btn btn-primary" href="#/question/new">${icon("plus")} 录入错题</a>
        </div>
      `;
      renderFloatingBar();
      return;
    }
    const hasMore = state.items.length < state.total;
    listWrap.innerHTML = `
      <div class="stack-sm">
        ${state.items
          .map((item) => {
            const badge = stateBadge(item.masteryState);
            const selected = state.selectedIds.has(item.id);
            const summary = toPlainText(item.questionText) || "图片题目 · 待补充题干";
            const archived = item.masteryState === "ARCHIVED";
            return `
              <div class="question-card ${selected ? "selected" : ""}" data-id="${escapeHtml(item.id)}">
                ${
                  state.paperMode
                    ? `<div class="check-circle ${selected ? "checked" : ""}">${selected ? icon("check") : ""}</div>`
                    : ""
                }
                <div class="question-card-body">
                  <div class="row-between">
                    <div class="row-gap">
                      <span class="pill ${badge.cls}">${badge.label}</span>
                      ${item.chapterName ? `<span class="pill">${escapeHtml(item.chapterName)}</span>` : ""}
                      ${item.errorReason ? `<span class="pill pill-red">${escapeHtml(item.errorReason)}</span>` : ""}
                      ${item.questionType ? `<span class="pill">${escapeHtml(item.questionType)}</span>` : ""}
                    </div>
                    <div class="row-gap">
                      <span style="font-size:12px;color:var(--text-muted);">${escapeHtml(item.questionDate || "")}</span>
                      ${
                        !state.paperMode
                          ? `<button type="button" class="btn btn-ghost btn-sm" data-archive-id="${escapeHtml(item.id)}" data-archived="${archived}">
                              ${archived ? "恢复" : "归档"}
                            </button>`
                          : ""
                      }
                    </div>
                  </div>
                  <div class="question-snippet">${escapeHtml(summary)}</div>
                  ${
                    (item.tags || []).length
                      ? `<div class="row-gap">${item.tags.map((t) => `<span class="pill"># ${escapeHtml(t)}</span>`).join("")}</div>`
                      : ""
                  }
                </div>
              </div>`;
          })
          .join("")}
      </div>
      ${
        hasMore
          ? `<div style="text-align:center;margin-top:18px;">
              <button class="btn btn-secondary" id="q-load-more" type="button" ${state.loading ? "disabled" : ""}>
                ${state.loading ? "加载中…" : `加载更多（已显示 ${state.items.length} / ${state.total}）`}
              </button>
            </div>`
          : `<p style="text-align:center;color:var(--text-muted);font-size:12.5px;margin-top:16px;">已展示全部 ${state.total} 道错题</p>`
      }
    `;

    listWrap.querySelectorAll(".question-card").forEach((card) => {
      card.addEventListener("click", (e) => {
        const archiveBtn = e.target.closest("[data-archive-id]");
        if (archiveBtn) {
          e.stopPropagation();
          handleArchive(archiveBtn.getAttribute("data-archive-id"), archiveBtn.getAttribute("data-archived") === "true");
          return;
        }
        const id = card.getAttribute("data-id");
        if (state.paperMode) {
          if (state.selectedIds.has(id)) state.selectedIds.delete(id);
          else state.selectedIds.add(id);
          renderListOnly();
        } else {
          window.location.hash = `#/question/${encodeURIComponent(id)}`;
        }
      });
    });

    listWrap.querySelector("#q-load-more")?.addEventListener("click", () => load(false));
    renderFloatingBar();
  }

  async function handleArchive(id, archived) {
    const ok = await confirmDialog({
      title: archived ? "恢复错题" : "归档错题",
      message: archived
        ? "恢复后，这道题会回到未归档错题，并按原有练习记录恢复状态。"
        : "归档后会从日常错题和练习范围中收起，记录与学习历史都会保留。",
      confirmText: archived ? "恢复" : "归档"
    });
    if (!ok) return;
    try {
      await request(`/questions/${id}/${archived ? "unarchive" : "archive"}`, { method: "POST" });
      toast(archived ? "已恢复到未归档" : "已归档", "success");
      load(true);
    } catch (err) {
      toast(err.message || "操作失败", "error");
    }
  }

  async function load(reset = true) {
    state.loading = true;
    if (reset) {
      state.page = 1;
      state.items = [];
    } else {
      state.page += 1;
    }
    renderListOnly();
    try {
      const q = queryString({
        keyword: state.keyword.trim(),
        state: state.status,
        subjectId: "history",
        chapterId: state.chapterId,
        errorReason: state.errorReason,
        tag: state.tag.trim(),
        dueOnly: state.dueOnly ? "true" : "",
        dateFrom: state.dateFrom,
        dateTo: state.dateTo,
        sort: state.sort,
        page: state.page,
        pageSize: state.pageSize
      });
      const res = await request("/questions" + q);
      state.total = Number(res.total || 0);
      state.items = reset ? res.items || [] : state.items.concat(res.items || []);
      state.loading = false;
      updateHeader();
      renderListOnly();
    } catch (err) {
      state.loading = false;
      listWrap.innerHTML = `<div class="card empty-state"><div class="empty-title">加载失败</div><p>${escapeHtml(err.message)}</p></div>`;
    }
  }

  // Event bindings
  const kwInput = container.querySelector("#q-keyword");
  container.querySelector("#q-search-btn").addEventListener("click", () => {
    state.keyword = kwInput.value;
    load(true);
  });
  kwInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      state.keyword = kwInput.value;
      load(true);
    }
  });
  container.querySelector("#q-sort").addEventListener("change", (e) => {
    state.sort = e.target.value;
    load(true);
  });
  container.querySelector("#q-filter-toggle").addEventListener("click", () => {
    state.showFilters = !state.showFilters;
    container.querySelector("#q-adv-panel").style.display = state.showFilters ? "block" : "none";
  });
  container.querySelector("#q-state-pills").addEventListener("click", (e) => {
    const btn = e.target.closest("[data-state]");
    if (!btn) return;
    state.status = btn.getAttribute("data-state");
    container.querySelectorAll("#q-state-pills .chip-btn").forEach((el) => el.classList.toggle("active", el === btn));
    load(true);
  });
  container.querySelector("#q-due-only").addEventListener("change", (e) => {
    state.dueOnly = e.target.checked;
    load(true);
  });
  container.querySelector("#q-chapter").addEventListener("change", (e) => {
    state.chapterId = e.target.value;
    load(true);
  });
  container.querySelector("#q-reason").addEventListener("change", (e) => {
    state.errorReason = e.target.value;
    load(true);
  });
  container.querySelector("#q-tag").addEventListener("change", (e) => {
    state.tag = e.target.value;
    load(true);
  });
  container.querySelector("#q-date-from").addEventListener("change", (e) => {
    state.dateFrom = e.target.value;
    load(true);
  });
  container.querySelector("#q-date-to").addEventListener("change", (e) => {
    state.dateTo = e.target.value;
    load(true);
  });
  container.querySelector("#q-reset-filters").addEventListener("click", () => {
    state.keyword = "";
    state.status = "";
    state.chapterId = "";
    state.errorReason = "";
    state.tag = "";
    state.dueOnly = false;
    state.dateFrom = "";
    state.dateTo = "";
    state.sort = "NEWEST";
    kwInput.value = "";
    container.querySelector("#q-sort").value = "NEWEST";
    container.querySelector("#q-due-only").checked = false;
    container.querySelector("#q-chapter").value = "";
    container.querySelector("#q-reason").value = "";
    container.querySelector("#q-tag").value = "";
    container.querySelector("#q-date-from").value = "";
    container.querySelector("#q-date-to").value = "";
    container.querySelectorAll("#q-state-pills .chip-btn").forEach((el) =>
      el.classList.toggle("active", el.getAttribute("data-state") === "")
    );
    load(true);
  });

  updateHeader();
  load(true);
}
