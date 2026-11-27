import { request, fetchAuthBlobUrl } from "../api.js";
import { toHtml } from "../markdown.js";
import { parsePracticeQuestion } from "../question-content.js";
import { escapeHtml, stateBadge, formatDateTime, toast, confirmDialog, openImageLightbox } from "../ui.js";
import { icon } from "../icons.js";

const RESULT_LABELS = {
  CORRECT: { text: "通过", cls: "pill-green" },
  PARTIAL: { text: "部分掌握", cls: "pill-amber" },
  INCORRECT: { text: "未通过", cls: "pill-red" },
  UNCERTAIN: { text: "未通过", cls: "pill-red" }
};

export async function renderQuestionDetail(container, ctx, questionId) {
  ctx.setHeader("错题详情", "查看结构化题干、参考解析、原图附件与复习历史", `
    <a class="btn btn-secondary btn-sm" href="#/questions">${icon("arrowLeft")} 返回列表</a>
  `);
  container.innerHTML = `<div class="loading-state"><div class="spinner"></div><p>正在加载错题详情…</p></div>`;

  let detail;
  let attachments = [];
  let answerVisible = false;

  try {
    detail = await request(`/questions/${questionId}`);
    attachments = await Promise.all(
      (detail.attachments || []).map(async (item) => {
        try {
          const blobUrl = await fetchAuthBlobUrl(item.fileUrl);
          return Object.assign({}, item, { blobUrl });
        } catch {
          return item;
        }
      })
    );
  } catch (err) {
    container.innerHTML = `<div class="card empty-state"><div class="empty-title">加载失败</div><p>${escapeHtml(err.message)}</p></div>`;
    return;
  }

  const q = detail.question;
  const parts = parsePracticeQuestion(q);
  const timeline = detail.timeline || [];
  let reflections = detail.reflections || [];
  const badge = stateBadge(q.masteryState);
  const archived = q.masteryState === "ARCHIVED";

  ctx.setHeader("错题详情", `${q.chapterName || "历史专项"} · ${badge.label}`, `
    <a class="btn btn-secondary btn-sm" href="#/questions">${icon("arrowLeft")} 返回列表</a>
    <button class="btn btn-tonal btn-sm" id="detail-practice-btn" type="button">${icon("sparkles")} 专项练习此题</button>
    <a class="btn btn-primary btn-sm" href="#/question/${encodeURIComponent(q.id)}/edit">${icon("edit")} 编辑</a>
  `);

  document.getElementById("detail-practice-btn")?.addEventListener("click", () => {
    sessionStorage.setItem("wrongbook.web.practiceTargetQuestionId", q.id);
    window.location.hash = "#/practice";
  });

  function render() {
    container.innerHTML = `
      <div class="split-layout">
        <div class="stack-md">
          <section class="card stack-md">
            <div class="row-between">
              <div class="row-gap">
                <span class="pill ${badge.cls}">${badge.label}</span>
                ${q.chapterName ? `<span class="pill">${escapeHtml(q.chapterName)}</span>` : ""}
                ${q.questionType ? `<span class="pill">${escapeHtml(q.questionType)}</span>` : ""}
                ${q.errorReason ? `<span class="pill pill-red">${escapeHtml(q.errorReason)}</span>` : ""}
              </div>
              <button type="button" class="btn btn-tonal btn-sm" id="btn-toggle-answer">
                ${answerVisible ? "收起参考答案与解析" : "查看参考答案与解析"}
              </button>
            </div>

            <div class="stack-md">
              ${parts
                .map(
                  (part, idx) => `
                  <div style="padding:${parts.length > 1 ? "16px" : "0"};${
                    parts.length > 1 ? "border:1px solid var(--border-subtle);border-radius:16px;" : ""
                  }">
                    ${parts.length > 1 ? `<div style="margin-bottom:8px;"><span class="pill pill-blue">第 ${idx + 1} 题</span></div>` : ""}
                    ${part.questionText ? toHtml(part.questionText) : `<p style="color:var(--text-muted);">（纯图片题干，请查看下方原始图片）</p>`}
                    ${
                      part.options.length
                        ? `<div class="stack-sm" style="margin-top:12px;">
                            ${part.options
                              .map((opt) => {
                                const isAns = answerVisible && part.answerKey && part.answerKey === opt.key;
                                return `
                                  <div class="option-card ${isAns ? "correct" : ""}" style="cursor:default;">
                                    <span class="option-key">${escapeHtml(opt.key)}</span>
                                    <div style="flex:1;">${toHtml(opt.text)}</div>
                                  </div>`;
                              })
                              .join("")}
                          </div>`
                        : ""
                    }
                    ${
                      answerVisible && (part.answerText || part.explanation)
                        ? `<div class="answer-box stack-sm" style="margin-top:12px;">
                            ${
                              part.answerText
                                ? `<div><strong style="color:var(--accent);">参考答案：</strong>${toHtml(part.answerText)}</div>`
                                : ""
                            }
                            ${
                              part.explanation && parts.length > 1
                                ? `<div><strong>本题解析：</strong>${toHtml(part.explanation)}</div>`
                                : ""
                            }
                          </div>`
                        : ""
                    }
                  </div>`
                )
                .join("")}
            </div>

            ${
              answerVisible
                ? `<div class="answer-box stack-sm">
                    ${q.ownAnswer ? `<div><strong style="color:var(--danger);">我的错答：</strong>${toHtml(q.ownAnswer)}</div>` : ""}
                    ${q.correctAnswer && parts.length === 1 ? `<div><strong style="color:var(--success);">参考答案：</strong>${toHtml(q.correctAnswer)}</div>` : ""}
                    ${q.explanation ? `<div><strong>答案解析：</strong>${toHtml(q.explanation)}</div>` : ""}
                    ${q.correction ? `<div><strong>订正笔记：</strong>${toHtml(q.correction)}</div>` : ""}
                    ${!q.ownAnswer && !q.correctAnswer && !q.explanation && !q.correction ? `<span style="color:var(--text-muted);">暂未补充参考答案或解析，点击右上角「编辑」即可补充。</span>` : ""}
                  </div>`
                : ""
            }
          </section>

          ${
            attachments.length
              ? `<section class="card">
                  <div class="card-header">
                    <div>
                      <h3 class="card-title">原始题目图片（${attachments.length}）</h3>
                      <p class="card-desc">点击图片可高清大图预览</p>
                    </div>
                  </div>
                  <div class="photo-grid">
                    ${attachments
                      .map(
                        (att) => `
                        <div class="photo-tile">
                          <img src="${escapeHtml(att.blobUrl || "")}" alt="${escapeHtml(att.fileName || "原图")}" data-preview="${escapeHtml(
                          att.blobUrl || ""
                        )}" />
                        </div>`
                      )
                      .join("")}
                  </div>
                </section>`
              : ""
          }

          <section class="card stack-md">
            <div>
              <h3 class="card-title">个人思考与复盘随笔</h3>
              <p class="card-desc">随时记录重做后的新理解、史实关联与易错提醒</p>
            </div>
            <div class="stack-sm">
              <textarea class="textarea" id="reflection-input" placeholder="写下你对这道历史题的复盘思考…" style="min-height:84px;"></textarea>
              <div style="display:flex;justify-content:flex-end;">
                <button class="btn btn-primary btn-sm" id="btn-save-reflection" type="button">保存随笔</button>
              </div>
            </div>
            ${
              reflections.length
                ? `<div class="timeline">
                    ${reflections
                      .map(
                        (r) => `
                        <div class="timeline-item">
                          <div class="timeline-dot"></div>
                          <div style="flex:1;">
                            <div style="font-size:13.5px;color:var(--text-primary);white-space:pre-wrap;">${escapeHtml(r.content)}</div>
                            <div style="font-size:11.5px;color:var(--text-muted);margin-top:4px;">${formatDateTime(r.createdAt)}</div>
                          </div>
                        </div>`
                      )
                      .join("")}
                  </div>`
                : `<p style="margin:0;font-size:13px;color:var(--text-muted);">还没有写过随笔，复盘时记一句能大幅加深记忆。</p>`
            }
          </section>
        </div>

        <aside class="stack-md">
          <section class="card stack-md">
            <h3 class="card-title">题目档案</h3>
            <div class="stack-sm" style="font-size:13px;">
              <div class="row-between"><span style="color:var(--text-muted);">掌握状态</span><span class="pill ${badge.cls}">${badge.label}</span></div>
              <div class="row-between"><span style="color:var(--text-muted);">做题日期</span><span>${escapeHtml(q.questionDate || "—")}</span></div>
              <div class="row-between"><span style="color:var(--text-muted);">题目来源</span><span>${escapeHtml(q.source || "—")}</span></div>
              <div class="row-between"><span style="color:var(--text-muted);">难度评级</span><span>${q.difficulty ? `${q.difficulty} / 5` : "—"}</span></div>
              <div class="row-between"><span style="color:var(--text-muted);">下次复习</span><span>${formatDateTime(q.nextReviewAt)}</span></div>
            </div>
            ${
              (q.tags || []).length
                ? `<div class="row-gap" style="padding-top:8px;border-top:1px solid var(--border-subtle);">
                    ${q.tags.map((t) => `<span class="pill"># ${escapeHtml(t)}</span>`).join("")}
                  </div>`
                : ""
            }
            <div class="stack-sm" style="padding-top:10px;border-top:1px solid var(--border-subtle);">
              <button type="button" class="btn btn-secondary" id="btn-archive-toggle" style="width:100%;">
                ${icon("archive")} ${archived ? "取消归档（恢复到错题库）" : "归档此题"}
              </button>
              <button type="button" class="btn btn-danger" id="btn-trash-question" style="width:100%;">
                ${icon("trash")} 移入回收站
              </button>
            </div>
          </section>

          <section class="card">
            <h3 class="card-title" style="margin-bottom:14px;">历史复习轨迹（${timeline.length}）</h3>
            ${
              timeline.length
                ? `<div class="timeline">
                    ${timeline
                      .map((ev) => {
                        const meta = RESULT_LABELS[ev.result] || { text: ev.result, cls: "pill-gray" };
                        return `
                          <div class="timeline-item">
                            <div class="timeline-dot"></div>
                            <div style="flex:1;">
                              <div class="row-between">
                                <span class="pill ${meta.cls}">${meta.text}</span>
                                <span style="font-size:11.5px;color:var(--text-muted);">${formatDateTime(ev.occurredAt)}</span>
                              </div>
                            </div>
                          </div>`;
                      })
                      .join("")}
                  </div>`
                : `<p style="margin:0;font-size:13px;color:var(--text-muted);">尚无练习记录，点击上方「专项练习此题」开始第一次复习。</p>`
            }
          </section>
        </aside>
      </div>
    `;

    container.querySelector("#btn-toggle-answer")?.addEventListener("click", () => {
      answerVisible = !answerVisible;
      render();
    });

    container.querySelectorAll("[data-preview]").forEach((img) => {
      img.addEventListener("click", () => openImageLightbox(img.getAttribute("data-preview")));
    });

    container.querySelector("#btn-save-reflection")?.addEventListener("click", async () => {
      const input = container.querySelector("#reflection-input");
      const content = input?.value.trim();
      if (!content) return toast("请先写下你的思考", "error");
      try {
        const saved = await request(`/questions/${q.id}/reflections`, {
          method: "POST",
          data: { content, partNumber: null }
        });
        reflections = [saved].concat(reflections);
        toast("随笔已保存", "success");
        render();
      } catch (err) {
        toast(err.message || "保存失败", "error");
      }
    });

    container.querySelector("#btn-archive-toggle")?.addEventListener("click", async () => {
      const ok = await confirmDialog({
        title: archived ? "恢复错题" : "归档错题",
        message: archived
          ? "这道题会回到未归档错题，并根据原有练习记录恢复状态。"
          : "归档后不会进入日常练习队列，但仍可在已归档列表中查看。",
        confirmText: archived ? "恢复" : "归档"
      });
      if (!ok) return;
      try {
        await request(`/questions/${q.id}/${archived ? "unarchive" : "archive"}`, { method: "POST" });
        toast(archived ? "已恢复到未归档" : "已归档", "success");
        renderQuestionDetail(container, ctx, questionId);
      } catch (err) {
        toast(err.message || "操作失败", "error");
      }
    });

    container.querySelector("#btn-trash-question")?.addEventListener("click", async () => {
      const ok = await confirmDialog({
        title: "移入回收站",
        message: "错题及复习历史会保留在回收站中，可随时恢复。",
        confirmText: "移入回收站",
        danger: true
      });
      if (!ok) return;
      try {
        await request(`/questions/${q.id}`, { method: "DELETE" });
        toast("已移入回收站", "success");
        window.location.hash = "#/questions";
      } catch (err) {
        toast(err.message || "操作失败", "error");
      }
    });
  }

  render();
}
