import { request } from "../api.js";
import { toPlainText } from "../markdown.js";
import { escapeHtml, stateBadge } from "../ui.js";
import { icon } from "../icons.js";

const IMAGE_DRAFT_TITLE = "图片题目（待补充）";

export async function renderHome(container, ctx) {
  ctx.setHeader("首页概览", "历史专项错题统计、复习提醒与快捷入口", `
    <a class="btn btn-tonal btn-sm" href="#/practice">${icon("sparkles")} 开始智能练习</a>
    <a class="btn btn-primary btn-sm" href="#/question/new">${icon("camera")} 拍照 / 录入错题</a>
  `);

  container.innerHTML = `<div class="loading-state"><div class="spinner"></div><p>正在加载首页学习概览…</p></div>`;

  try {
    const [profile, overview] = await Promise.all([
      request("/me"),
      request("/dashboard?days=7")
    ]);
    ctx.updateSidebarProfile(profile);

    const mastery = Object.assign(
      { totalActive: 0, newCount: 0, reviewCount: 0, masteredCount: 0, archivedCount: 0, masteryRatePercent: 0 },
      overview.masteryBreakdown || {}
    );
    const totalActive = Number(mastery.totalActive || 0);
    const dueCount = Number(overview.dueCount || 0);
    const overdueCount = Number(overview.overdueCount || 0);
    const accuracy = overview.accuracyPercent === null || overview.accuracyPercent === undefined
      ? "—"
      : `${overview.accuracyPercent}%`;

    const reasons = overview.errorReasonStats || [];
    const maxReason = reasons.reduce((m, r) => Math.max(m, Number(r.count || 0)), 1);

    const chapters = overview.weakChapters || [];
    const maxChapter = chapters.reduce((m, c) => Math.max(m, Number(c.count || 0)), 1);

    const recent = overview.recentQuestions || [];

    const pctMastered = totalActive ? Math.round((mastery.masteredCount * 100) / totalActive) : 0;
    const pctReview = totalActive ? Math.round((mastery.reviewCount * 100) / totalActive) : 0;
    const pctNew = totalActive ? Math.max(0, 100 - pctMastered - pctReview) : 0;

    container.innerHTML = `
      <div class="stack-lg">
        <section class="hero-banner">
          <div>
            <div class="hero-badge">${icon("sparkles")} 历史专项 · 智能复习计划</div>
            <h2 class="hero-title">${dueCount > 0 ? `今天有 ${dueCount} 道历史错题等待复习` : "太棒了，当前没有到期错题"}</h2>
            <p class="hero-desc">${
              overdueCount > 0
                ? `其中 ${overdueCount} 道已超过复习节点，建议优先完成一轮练习巩固记忆。`
                : "完成练习后将按艾宾浩斯遗忘曲线自动更新掌握度与下次复习时间。"
            }</p>
          </div>
          <div class="row-gap">
            <a class="btn btn-primary" href="#/practice">${icon("sparkles")} 立即开始练习</a>
            <a class="btn btn-secondary" href="#/question/new">${icon("camera")} 传图 AI 识题</a>
          </div>
        </section>

        <section class="grid-4">
          <div class="kpi-card">
            <div class="kpi-label">待复习错题 <span class="pill pill-amber">艾宾浩斯</span></div>
            <div class="kpi-value accent">${dueCount}</div>
            <div class="kpi-note">${overdueCount > 0 ? `含 ${overdueCount} 道已逾期` : "复习节奏良好"}</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">近 7 天正确率 <span class="pill pill-green">练习</span></div>
            <div class="kpi-value success">${accuracy}</div>
            <div class="kpi-note">共练习 ${overview.reviewSampleCount || 0} 次 · 通过 ${overview.correctCount || 0} 次</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">当前掌握率 <span class="pill pill-blue">总览</span></div>
            <div class="kpi-value">${mastery.masteryRatePercent || 0}%</div>
            <div class="kpi-note">活跃 ${totalActive} 题 · 已通过 ${mastery.masteredCount} 题</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-label">反复易错题 <span class="pill pill-red">重点</span></div>
            <div class="kpi-value warning">${overview.repeatedErrorCount || 0}</div>
            <div class="kpi-note">累计未通过 2 次及以上的错题</div>
          </div>
        </section>

        <section class="card">
          <div class="card-header">
            <div>
              <h3 class="card-title">掌握度结构分布</h3>
              <p class="card-desc">历史专项未归档错题 ${totalActive} 题，已归档 ${mastery.archivedCount} 题</p>
            </div>
            <a class="btn btn-ghost btn-sm" href="#/questions">查看全部错题 →</a>
          </div>
          <div class="segmented-bar" aria-label="掌握度分布条">
            <div class="seg-mastered" style="width:${pctMastered}%"></div>
            <div class="seg-review" style="width:${pctReview}%"></div>
            <div class="seg-new" style="width:${pctNew}%"></div>
          </div>
          <div class="row-gap" style="margin-top:14px;">
            <span class="pill pill-green">已通过 ${mastery.masteredCount}</span>
            <span class="pill pill-amber">待复习 ${mastery.reviewCount}</span>
            <span class="pill pill-blue">新收录 ${mastery.newCount}</span>
            <span class="pill pill-gray">已归档 ${mastery.archivedCount}</span>
          </div>
        </section>

        <section class="grid-2">
          <div class="card">
            <div class="card-header">
              <div>
                <h3 class="card-title">高频错因分析</h3>
                <p class="card-desc">定位最常见的历史失分环节</p>
              </div>
            </div>
            ${
              reasons.length
                ? `<div class="bar-list">
                    ${reasons
                      .map((r) => {
                        const pct = Math.max(10, Math.round((Number(r.count || 0) * 100) / maxReason));
                        return `
                          <div class="bar-row">
                            <div class="bar-meta">
                              <span>${escapeHtml(r.reason)}</span>
                              <strong>${r.count} 题</strong>
                            </div>
                            <div class="bar-track"><div class="bar-fill" style="width:${pct}%"></div></div>
                          </div>`;
                      })
                      .join("")}
                  </div>`
                : `<div class="empty-state" style="padding:28px 12px;">暂无错因统计，收录错题时标记错因即可生成分析。</div>`
            }
          </div>

          <div class="card">
            <div class="card-header">
              <div>
                <h3 class="card-title">薄弱历史章节</h3>
                <p class="card-desc">按未归档错题数量降序排列</p>
              </div>
            </div>
            ${
              chapters.length
                ? `<div class="bar-list">
                    ${chapters
                      .map((c) => {
                        const pct = Math.max(10, Math.round((Number(c.count || 0) * 100) / maxChapter));
                        return `
                          <div class="bar-row">
                            <div class="bar-meta">
                              <span>${escapeHtml(c.chapterName)}</span>
                              <span style="color:var(--text-muted);font-size:12px;">${c.count} 题 · 已通过 ${c.masteredCount || 0}</span>
                            </div>
                            <div class="bar-track"><div class="bar-fill amber" style="width:${pct}%"></div></div>
                          </div>`;
                      })
                      .join("")}
                  </div>`
                : `<div class="empty-state" style="padding:28px 12px;">暂无章节分布数据，先录入第一道历史错题吧。</div>`
            }
          </div>
        </section>

        <section class="card">
          <div class="card-header">
            <div>
              <h3 class="card-title">最近收录错题</h3>
              <p class="card-desc">点击任意卡片可直接查看解析、原图与随笔</p>
            </div>
            <div class="row-gap">
              <a class="btn btn-secondary btn-sm" href="#/papers">${icon("fileText")} 组卷中心</a>
              <a class="btn btn-tonal btn-sm" href="#/questions">全部错题</a>
            </div>
          </div>
          ${
            recent.length
              ? `<div class="stack-sm">
                  ${recent
                    .map((item) => {
                      const badge = stateBadge(item.masteryState);
                      const text =
                        item.questionText === IMAGE_DRAFT_TITLE
                          ? "图片题目 · 待补充题干"
                          : toPlainText(item.questionText) || "图片题目 · 待补充题干";
                      return `
                        <a class="question-card" href="#/question/${encodeURIComponent(item.id)}">
                          <div class="question-card-body">
                            <div class="row-between">
                              <div class="row-gap">
                                <span class="pill ${badge.cls}">${badge.label}</span>
                                <span class="pill">${escapeHtml(item.chapterName || item.subjectName || "历史")}</span>
                              </div>
                              <span style="font-size:12px;color:var(--text-muted);">练习 ${item.reviewCount || 0} 次</span>
                            </div>
                            <div class="question-snippet">${escapeHtml(text)}</div>
                          </div>
                        </a>`;
                    })
                    .join("")}
                </div>`
              : `<div class="empty-state">
                  <div class="empty-title">还没有收录历史错题</div>
                  <p>支持直接拖拽或上传题目与答案图片，AI 自动识别并对齐题号。</p>
                  <a class="btn btn-primary" href="#/question/new">${icon("plus")} 立即录入第一道错题</a>
                </div>`
          }
        </section>
      </div>
    `;
  } catch (err) {
    container.innerHTML = `
      <div class="card empty-state">
        <div class="empty-title">无法连接后端服务</div>
        <p>${escapeHtml(err.message)}</p>
        <p style="font-size:12px;color:var(--text-muted);">请确认 Spring Boot 后端已启动（默认 8080 端口），或在「个人档案」中检查接口地址。</p>
        <button class="btn btn-primary" id="btn-retry-home" type="button">重新连接</button>
      </div>
    `;
    container.querySelector("#btn-retry-home")?.addEventListener("click", () => renderHome(container, ctx));
  }
}
