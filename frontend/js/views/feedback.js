import { request } from "../api.js";
import { toast } from "../ui.js";

const CATEGORIES = [
  { key: "SUGGESTION", label: "功能建议" },
  { key: "BUG", label: "问题反馈" },
  { key: "HELP", label: "使用帮助" }
];

export function renderFeedback(container, ctx) {
  ctx.setHeader("意见反馈", "帮助我们持续优化「小 E 错题本」的历史学习体验");

  let category = "SUGGESTION";

  container.innerHTML = `
    <div style="max-width:680px;margin:0 auto;">
      <section class="card stack-md">
        <div class="field-group">
          <label class="field-label">反馈类型</label>
          <div class="row-gap" id="fb-cats">
            ${CATEGORIES.map(
              (c) => `<button type="button" class="chip-btn ${c.key === category ? "active" : ""}" data-cat="${c.key}">${c.label}</button>`
            ).join("")}
          </div>
        </div>

        <div class="field-group">
          <label class="field-label">详细说明</label>
          <textarea class="textarea textarea-lg" id="fb-content" placeholder="请描述你遇到的问题或希望新增的功能…"></textarea>
        </div>

        <div style="display:flex;justify-content:flex-end;">
          <button type="button" class="btn btn-primary" id="fb-submit">提交反馈</button>
        </div>
      </section>
    </div>
  `;

  container.querySelector("#fb-cats").addEventListener("click", (e) => {
    const btn = e.target.closest("[data-cat]");
    if (!btn) return;
    category = btn.getAttribute("data-cat");
    container.querySelectorAll("#fb-cats .chip-btn").forEach((el) => el.classList.toggle("active", el === btn));
  });

  const submitBtn = container.querySelector("#fb-submit");
  submitBtn.addEventListener("click", async () => {
    const textarea = container.querySelector("#fb-content");
    const content = textarea.value.trim();
    if (!content) return toast("请填写反馈内容", "error");
    submitBtn.disabled = true;
    try {
      const res = await request("/feedback", {
        method: "POST",
        data: { category, content }
      });
      toast(res.message || "反馈已保存，感谢你的建议", "success");
      textarea.value = "";
    } catch (err) {
      toast(err.message || "提交失败", "error");
    } finally {
      submitBtn.disabled = false;
    }
  });
}
