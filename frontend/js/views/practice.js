import { request, queryString } from "../api.js";
import { toHtml, toPlainText } from "../markdown.js";
import { parsePracticeQuestion } from "../question-content.js";
import { escapeHtml, toast } from "../ui.js";
import { icon } from "../icons.js";

const SCOPE_OPTIONS = [
  { key: "ALL", label: "全部未归档", desc: "按复习到期时间优先抽取历史错题，每轮最多 10 题" },
  { key: "DUE", label: "到期优先", desc: "只抽取已到艾宾浩斯复习节点的历史错题，集中巩固" },
  { key: "RECENT_7D", label: "最近 7 天", desc: "只抽取最近 7 天新收录的历史错题，适合趁热打铁" }
];

const RATINGS = [
  { value: 1, label: "完全不会" },
  { value: 2, label: "不太会" },
  { value: 3, label: "部分掌握" },
  { value: 4, label: "基本掌握" },
  { value: 5, label: "完全掌握" }
];

const RESULT_LABELS = {
  CORRECT: { text: "本次通过", cls: "pill-green" },
  PARTIAL: { text: "部分掌握", cls: "pill-amber" },
  INCORRECT: { text: "未通过", cls: "pill-red" },
  UNCERTAIN: { text: "未通过", cls: "pill-red" }
};

function partAnswered(part, submitted) {
  if (!part || !submitted) return false;
  if (part.isMultipleChoice && part.answerKey) return typeof part.isCorrect === "boolean";
  return Number(part.masteryRating) >= 1 && Number(part.masteryRating) <= 5;
}

function canSubmit(item) {
  return (item.practiceParts || []).every((p) => (p.isMultipleChoice ? !!p.selectedKey : true));
}

function aggregateRating(parts) {
  const ratings = parts.map((p) => Number(p.masteryRating || 0));
  if (!ratings.length || ratings.some((r) => r < 1 || r > 5)) return 0;
  return Math.min(...ratings);
}

export async function renderPractice(container, ctx) {
  ctx.setHeader("智能练习", "选择题自动判分，主观材料题先思考再翻卡自评，自动更新艾宾浩斯复习计划");

  const state = {
    scope: "ALL",
    chapterId: "",
    chapters: [],
    sessionId: "",
    items: [],
    index: 0,
    solutions: [],
    started: false,
    completed: false,
    busy: false,
    totalCount: 0,
    correctCount: 0,
    questionStartedAt: 0
  };

  try {
    state.chapters = (await request("/taxonomy/chapters" + queryString({ subjectId: "history" }))) || [];
  } catch {}

  const targetQid = sessionStorage.getItem("wrongbook.web.practiceTargetQuestionId");
  if (targetQid) {
    sessionStorage.removeItem("wrongbook.web.practiceTargetQuestionId");
  }

  async function startSession(questionId = null) {
    if (state.busy) return;
    state.busy = true;
    render();
    try {
      const res = await request("/practice-sessions", {
        method: "POST",
        data: {
          subjectId: "history",
          chapterId: state.chapterId || null,
          dueOnly: state.scope === "DUE",
          scope: state.scope,
          limit: 10,
          questionId
        }
      });
      state.busy = false;
      if (!res.totalCount) {
        toast("当前条件下没有可练习的未归档历史错题", "error");
        render();
        return;
      }
      state.sessionId = res.sessionId;
      state.items = (res.items || []).map((it) =>
        Object.assign({}, it, {
          submitted: false,
          practiceParts: parsePracticeQuestion(it)
        })
      );
      state.index = 0;
      state.solutions = [];
      state.started = true;
      state.completed = false;
      state.totalCount = res.totalCount;
      state.correctCount = 0;
      state.questionStartedAt = Date.now();
      render();
    } catch (err) {
      state.busy = false;
      toast(err.message || "开始练习失败", "error");
      render();
    }
  }

  function renderSetup() {
    container.innerHTML = `
      <div class="stack-lg" style="max-width:760px;margin:0 auto;">
        <section class="card stack-md">
          <div>
            <h2 class="card-title" style="font-size:18px;">选择本轮练习范围</h2>
            <p class="card-desc">掌握度达到 4 档及以上并连续通过 3 次后，系统将自动标记为「已通过」</p>
          </div>

          <div class="stack-sm" id="scope-list">
            ${SCOPE_OPTIONS.map(
              (opt) => `
              <button type="button" class="mode-card ${state.scope === opt.key ? "active" : ""}" data-scope="${opt.key}">
                <div class="mode-card-title">${opt.label}</div>
                <div class="mode-card-desc">${opt.desc}</div>
              </button>`
            ).join("")}
          </div>

          <div class="field-group">
            <label class="field-label">限定历史章节（选填）</label>
            <select class="select" id="practice-chapter">
              <option value="">全部历史章节</option>
              ${state.chapters
                .map(
                  (c) =>
                    `<option value="${escapeHtml(c.id)}" ${c.id === state.chapterId ? "selected" : ""}>${escapeHtml(
                      c.name
                    )}</option>`
                )
                .join("")}
            </select>
          </div>

          <button type="button" class="btn btn-primary" id="btn-start-practice" style="width:100%;height:46px;" ${
            state.busy ? "disabled" : ""
          }>
            ${icon("sparkles")} ${state.busy ? "正在抽题…" : "开始本轮智能练习"}
          </button>
        </section>
      </div>
    `;

    container.querySelector("#scope-list")?.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-scope]");
      if (!btn) return;
      state.scope = btn.getAttribute("data-scope");
      renderSetup();
    });
    container.querySelector("#practice-chapter")?.addEventListener("change", (e) => {
      state.chapterId = e.target.value;
    });
    container.querySelector("#btn-start-practice")?.addEventListener("click", () => startSession(null));
  }

  function renderActive() {
    const item = state.items[state.index];
    const progressPct = Math.round(((state.index + 1) * 100) / Math.max(1, state.items.length));
    const readyForNext = item.submitted && item.practiceParts.every((p) => partAnswered(p, true));

    container.innerHTML = `
      <div class="stack-md" style="max-width:860px;margin:0 auto;">
        <div class="card" style="padding:16px 22px;">
          <div class="row-between" style="margin-bottom:8px;">
            <div class="row-gap">
              <span class="pill pill-blue">第 ${state.index + 1} / ${state.items.length} 题</span>
              ${item.chapterName ? `<span class="pill">${escapeHtml(item.chapterName)}</span>` : ""}
              ${item.errorReason ? `<span class="pill pill-red">${escapeHtml(item.errorReason)}</span>` : ""}
            </div>
            <button type="button" class="btn btn-ghost btn-sm" id="btn-quit-practice">结束本轮</button>
          </div>
          <div class="bar-track"><div class="bar-fill" style="width:${progressPct}%"></div></div>
        </div>

        <div class="stack-md">
          ${item.practiceParts
            .map(
              (part, pIdx) => `
              <section class="card stack-md">
                ${
                  item.practiceParts.length > 1
                    ? `<div><span class="pill pill-blue">小题 ${pIdx + 1}</span></div>`
                    : ""
                }
                <div>${toHtml(part.questionText || "（纯图片题干）")}</div>

                ${
                  part.isMultipleChoice
                    ? `<div class="stack-sm">
                        ${part.options
                          .map((opt) => {
                            let cls = "";
                            if (item.submitted) {
                              if (part.answerKey && opt.key === part.answerKey) cls = "correct";
                              else if (part.selectedKey === opt.key && part.answerKey && opt.key !== part.answerKey) cls = "wrong";
                              else if (part.selectedKey === opt.key) cls = "selected";
                            } else if (part.selectedKey === opt.key) {
                              cls = "selected";
                            }
                            return `
                              <button type="button" class="option-card ${cls}" data-part="${pIdx}" data-opt="${opt.key}" ${
                              item.submitted ? "disabled" : ""
                            }>
                                <span class="option-key">${opt.key}</span>
                                <div style="flex:1;">${toHtml(opt.text)}</div>
                              </button>`;
                          })
                          .join("")}
                      </div>`
                    : `<div class="field-group">
                        <label class="field-label">你的作答思路 <span class="field-hint">选填，可直接心中作答后翻卡核对</span></label>
                        <textarea class="textarea" data-subjective-part="${pIdx}" placeholder="写下你的核心史实要点…" ${
                          item.submitted ? "disabled" : ""
                        }>${escapeHtml(part.studentAnswer || "")}</textarea>
                      </div>`
                }

                ${
                  item.submitted
                    ? `<div class="answer-box stack-sm">
                        <div class="row-between">
                          <strong style="color:var(--accent);">参考答案与解析</strong>
                          ${
                            part.isMultipleChoice && part.answerKey
                              ? `<span class="pill ${part.isCorrect ? "pill-green" : "pill-red"}">${
                                  part.isCorrect ? "回答正确" : `回答有误 · 正确答案 ${part.answerKey}`
                                }</span>`
                              : `<span class="pill pill-amber">请对照解析自评掌握度</span>`
                          }
                        </div>
                        ${part.answerText ? `<div><strong>答案：</strong>${toHtml(part.answerText)}</div>` : ""}
                        ${part.explanation ? `<div><strong>解析：</strong>${toHtml(part.explanation)}</div>` : ""}
                        ${
                          !part.isMultipleChoice || !part.answerKey
                            ? `<div style="margin-top:8px;">
                                <div style="font-size:12.5px;font-weight:600;margin-bottom:8px;">选择掌握程度（1~5 档）：</div>
                                <div class="rating-grid">
                                  ${RATINGS.map(
                                    (r) => `
                                    <button type="button" class="rating-btn ${part.masteryRating === r.value ? "active" : ""}" data-rate-part="${pIdx}" data-rate="${r.value}">
                                      <span class="rating-num">${r.value}</span>
                                      <span class="rating-text">${r.label}</span>
                                    </button>`
                                  ).join("")}
                                </div>
                              </div>`
                            : ""
                        }
                        <div style="margin-top:8px;padding-top:10px;border-top:1px solid #d3e3fd;">
                          <div class="row-between" style="margin-bottom:6px;">
                            <span style="font-size:12.5px;font-weight:600;">随手记一句复盘随笔（选填）</span>
                            ${part.reflectionSaved ? `<span class="pill pill-green">已保存</span>` : ""}
                          </div>
                          <div style="display:flex;gap:8px;">
                            <input class="input" data-reflect-part="${pIdx}" value="${escapeHtml(part.studentReflection || "")}" placeholder="如：注意区分时间节点与因果关系" />
                            <button type="button" class="btn btn-secondary btn-sm" data-save-reflect="${pIdx}">保存</button>
                          </div>
                        </div>
                      </div>`
                    : ""
                }
              </section>`
            )
            .join("")}
        </div>

        <div class="row-between">
          <a class="btn btn-ghost btn-sm" href="#/question/${encodeURIComponent(item.id)}" target="_blank">查看原题详情</a>
          ${
            !item.submitted
              ? `<button type="button" class="btn btn-primary" id="btn-submit-part" ${canSubmit(item) ? "" : "disabled"}>
                  提交本题并核对答案
                </button>`
              : `<button type="button" class="btn btn-primary" id="btn-next-question" ${readyForNext && !state.busy ? "" : "disabled"}>
                  ${state.busy ? "提交中…" : state.index + 1 < state.items.length ? "下一题 →" : "完成本轮练习"}
                </button>`
          }
        </div>
      </div>
    `;

    container.querySelector("#btn-quit-practice")?.addEventListener("click", () => {
      state.started = false;
      state.completed = false;
      render();
    });

    container.querySelectorAll("[data-opt]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const pIdx = Number(btn.getAttribute("data-part"));
        item.practiceParts[pIdx].selectedKey = btn.getAttribute("data-opt");
        renderActive();
      });
    });

    container.querySelectorAll("[data-subjective-part]").forEach((ta) => {
      ta.addEventListener("input", () => {
        const pIdx = Number(ta.getAttribute("data-subjective-part"));
        item.practiceParts[pIdx].studentAnswer = ta.value;
      });
    });

    container.querySelector("#btn-submit-part")?.addEventListener("click", () => {
      item.submitted = true;
      item.practiceParts.forEach((part) => {
        part.answerRevealed = true;
        if (part.isMultipleChoice && part.answerKey) {
          part.isCorrect = part.selectedKey === part.answerKey;
          part.masteryRating = part.isCorrect ? 5 : 1;
        }
      });
      renderActive();
    });

    container.querySelectorAll("[data-rate]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const pIdx = Number(btn.getAttribute("data-rate-part"));
        item.practiceParts[pIdx].masteryRating = Number(btn.getAttribute("data-rate"));
        renderActive();
      });
    });

    container.querySelectorAll("[data-reflect-part]").forEach((inp) => {
      inp.addEventListener("input", () => {
        const pIdx = Number(inp.getAttribute("data-reflect-part"));
        item.practiceParts[pIdx].studentReflection = inp.value;
        item.practiceParts[pIdx].reflectionSaved = false;
      });
    });

    container.querySelectorAll("[data-save-reflect]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const pIdx = Number(btn.getAttribute("data-save-reflect"));
        const part = item.practiceParts[pIdx];
        const content = String(part.studentReflection || "").trim();
        if (!content) return toast("请先写下复盘内容", "error");
        try {
          await request(`/questions/${item.id}/reflections`, {
            method: "POST",
            data: { content, partNumber: Number(part.number) || pIdx + 1 }
          });
          part.reflectionSaved = true;
          toast("随笔已保存", "success");
          renderActive();
        } catch (err) {
          toast(err.message || "保存失败", "error");
        }
      });
    });

    container.querySelector("#btn-next-question")?.addEventListener("click", async () => {
      const rating = aggregateRating(item.practiceParts);
      if (!rating) return toast("请先完成掌握度自评", "error");
      state.busy = true;
      renderActive();
      const durationSeconds = state.questionStartedAt
        ? Math.max(0, Math.round((Date.now() - state.questionStartedAt) / 1000))
        : null;
      try {
        const res = await request(`/practice-sessions/${state.sessionId}/answers`, {
          method: "POST",
          data: { answers: [{ questionId: item.id, rating, durationSeconds }] }
        });
        state.busy = false;
        state.solutions = state.solutions.concat(res.results || []);
        state.correctCount = res.correctCount || 0;
        if (res.completed || state.index + 1 >= state.items.length) {
          state.completed = true;
          state.started = false;
        } else {
          state.index += 1;
          state.questionStartedAt = Date.now();
        }
        render();
      } catch (err) {
        state.busy = false;
        toast(err.message || "提交结果失败", "error");
        renderActive();
      }
    });
  }

  function renderSummary() {
    const rate = state.totalCount ? Math.round((state.correctCount * 100) / state.totalCount) : 0;
    container.innerHTML = `
      <div class="stack-lg" style="max-width:820px;margin:0 auto;">
        <section class="card" style="text-align:center;padding:34px 24px;">
          <span class="pill pill-green">本轮练习已完成</span>
          <h2 style="font-size:28px;margin:12px 0 6px;">通过率 ${rate}%</h2>
          <p style="color:var(--text-muted);margin:0 0 20px;">本轮共练习 ${state.totalCount} 题，通过 ${state.correctCount} 题，复习计划已自动更新。</p>
          <div class="row-gap" style="justify-content:center;">
            <button type="button" class="btn btn-primary" id="btn-practice-again">${icon("sparkles")} 再练一轮</button>
            <a class="btn btn-secondary" href="#/home">返回首页概览</a>
          </div>
        </section>

        <section class="card stack-sm">
          <h3 class="card-title">本轮题目结果明细</h3>
          ${state.solutions
            .map((sol) => {
              const meta = RESULT_LABELS[sol.submittedResult] || { text: sol.submittedResult, cls: "pill-gray" };
              return `
                <a class="question-card" href="#/question/${encodeURIComponent(sol.questionId || sol.id)}">
                  <div class="question-card-body">
                    <div class="row-between">
                      <span class="pill ${meta.cls}">${meta.text}</span>
                      <span style="font-size:12px;color:var(--text-muted);">点击查看详情 →</span>
                    </div>
                    <div class="question-snippet">${escapeHtml(toPlainText(sol.questionText) || "图片题目")}</div>
                  </div>
                </a>`;
            })
            .join("")}
        </section>
      </div>
    `;

    container.querySelector("#btn-practice-again")?.addEventListener("click", () => {
      state.completed = false;
      state.started = false;
      render();
    });
  }

  function render() {
    if (state.completed) renderSummary();
    else if (state.started) renderActive();
    else renderSetup();
  }

  if (targetQid) startSession(targetQid);
  else render();
}
