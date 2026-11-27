import { request } from "../api.js";
import { toPlainText } from "../markdown.js";
import { escapeHtml, toast } from "../ui.js";
import { icon } from "../icons.js";

export async function renderTrash(container, ctx) {
  ctx.setHeader("回收站", "已删除的历史错题及其复习记录会保留在这里，可一键恢复");

  async function load() {
    container.innerHTML = `<div class="loading-state"><div class="spinner"></div><p>正在加载回收站…</p></div>`;
    try {
      const items = (await request("/questions/trash")) || [];
      if (!items.length) {
        container.innerHTML = `
          <div class="card empty-state">
            <div class="empty-title">回收站是空的</div>
            <p>移入回收站的错题会显示在这里，随时支持恢复。</p>
            <a class="btn btn-secondary" href="#/questions">返回历史错题库</a>
          </div>
        `;
        return;
      }
      container.innerHTML = `
        <div class="stack-sm">
          ${items
            .map((item) => {
              const summary = toPlainText(item.questionText) || "图片题目 · 待补充题干";
              return `
                <div class="question-card" style="cursor:default;">
                  <div class="question-card-body">
                    <div class="row-between">
                      <div class="row-gap">
                        <span class="pill pill-gray">已移入回收站</span>
                        ${item.chapterName ? `<span class="pill">${escapeHtml(item.chapterName)}</span>` : ""}
                      </div>
                      <button type="button" class="btn btn-tonal btn-sm" data-restore-id="${escapeHtml(item.id)}">
                        ${icon("refresh")} 恢复到错题库
                      </button>
                    </div>
                    <div class="question-snippet">${escapeHtml(summary)}</div>
                  </div>
                </div>`;
            })
            .join("")}
        </div>
      `;

      container.querySelectorAll("[data-restore-id]").forEach((btn) => {
        btn.addEventListener("click", async () => {
          btn.disabled = true;
          try {
            await request(`/questions/${btn.getAttribute("data-restore-id")}/restore`, { method: "POST" });
            toast("错题已恢复", "success");
            load();
          } catch (err) {
            btn.disabled = false;
            toast(err.message || "恢复失败", "error");
          }
        });
      });
    } catch (err) {
      container.innerHTML = `<div class="card empty-state"><div class="empty-title">加载失败</div><p>${escapeHtml(err.message)}</p></div>`;
    }
  }

  load();
}
