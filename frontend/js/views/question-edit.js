import { request, uploadFile, fetchAuthBlobUrl, queryString, authState } from "../api.js";
import { toHtml, formatRecognitionText } from "../markdown.js";
import { escapeHtml, todayString, toast, promptDialog, openImageLightbox } from "../ui.js";
import { icon } from "../icons.js";

const IMAGE_DRAFT_TITLE = "图片题目（待补充）";
const REASONS = ["概念不清", "审题失误", "计算错误", "步骤遗漏", "方法不熟", "其他"];
const QUESTION_TYPES = ["选择题", "材料分析题", "填空题", "简答题", "论述题", "其他"];

function createKey() {
  return "q-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
}

function appendRecognizedText(existing, incoming) {
  const current = String(existing || "").trim();
  const added = String(incoming || "").trim();
  if (!added) return current;
  if (!current || current === added || current.includes(added)) return current || added;
  if (added.includes(current)) return added;
  return current + "\n\n" + added;
}

function renumberTextFrom(text, startNum) {
  let current = startNum;
  return String(text || "")
    .split(/\r?\n/)
    .map((line) => {
      const match = line.trim().match(/^(?:\*\*)?(?:第\s*)?(\d{1,3})\s*(?:题)?[.、．:：)）](?:\*\*)?\s*(.*)$/);
      if (match) return `${current++}. ${(match[2] || "").trim()}`;
      return line;
    })
    .join("\n");
}

function alignRecognizedAnswers(form, originalNumbers, answerMap, explanationMap) {
  const numbers = Array.isArray(originalNumbers) ? originalNumbers : [];
  const aMap = answerMap || {};
  const eMap = explanationMap || {};
  const hasAnswerKeys = Object.keys(aMap).length > 0;
  const hasExplanationKeys = Object.keys(eMap).length > 0;
  if (!numbers.length || (!hasAnswerKeys && !hasExplanationKeys)) {
    return { matchedAnswers: 0, totalQuestions: numbers.length, missingIndexes: [] };
  }
  const answerLines = [];
  const explanationLines = [];
  const missingIndexes = [];
  numbers.forEach((origNum, idx) => {
    const key = String(origNum);
    const newNum = idx + 1;
    if (aMap[key]) answerLines.push(numbers.length === 1 ? aMap[key] : `${newNum}. ${aMap[key]}`);
    else if (hasAnswerKeys) missingIndexes.push(newNum);
    if (eMap[key]) explanationLines.push(numbers.length === 1 ? eMap[key] : `${newNum}. ${eMap[key]}`);
  });
  if (answerLines.length) form.correctAnswer = answerLines.join("\n");
  if (explanationLines.length) form.explanation = explanationLines.join("\n");
  return { matchedAnswers: answerLines.length, totalQuestions: numbers.length, missingIndexes };
}

export async function renderQuestionEdit(container, ctx, questionId = "") {
  const editing = !!questionId;
  ctx.setHeader(
    editing ? "编辑历史错题" : "拍照 / 录入错题",
    "支持多图上传与 AI 自动识题，题卷与答案页可跨页按题号自动对齐",
    `<button class="btn btn-secondary btn-sm" id="edit-back-btn" type="button">${icon("arrowLeft")} 返回</button>`
  );
  document.getElementById("edit-back-btn")?.addEventListener("click", () => history.back());

  const state = {
    id: questionId,
    saving: false,
    recognizing: false,
    recognitionHint: "",
    recognitionFailed: false,
    previewMode: false,
    originalNumbers: [],
    answerMap: {},
    explanationMap: {},
    chapters: [],
    knowledgePoints: [],
    pendingPhotos: [], // { file, previewUrl, status, message, idempotencyKey }
    existingPhotos: [], // { id, fileUrl, blobUrl }
    form: {
      idempotencyKey: createKey(),
      questionText: "",
      ownAnswer: "",
      correctAnswer: "",
      correction: "",
      explanation: "",
      subjectId: "history",
      chapterId: "",
      knowledgePointIds: [],
      errorReason: "",
      source: "",
      questionDate: todayString(),
      difficulty: "",
      questionType: "",
      tagsText: "",
      version: null
    }
  };

  container.innerHTML = `<div class="loading-state"><div class="spinner"></div><p>正在准备录题工作台…</p></div>`;

  try {
    const [chapters, detail] = await Promise.all([
      request("/taxonomy/chapters" + queryString({ subjectId: "history" })),
      editing ? request(`/questions/${questionId}`) : Promise.resolve(null)
    ]);
    state.chapters = chapters || [];

    if (editing && detail && detail.question) {
      const q = detail.question;
      Object.assign(state.form, {
        questionText: q.questionText === IMAGE_DRAFT_TITLE ? "" : q.questionText || "",
        ownAnswer: q.ownAnswer || "",
        correctAnswer: q.correctAnswer || "",
        correction: q.correction || "",
        explanation: q.explanation || "",
        subjectId: q.subjectId || "history",
        chapterId: q.chapterId || "",
        knowledgePointIds: q.knowledgePointIds || [],
        errorReason: q.errorReason || "",
        source: q.source || "",
        questionDate: q.questionDate || todayString(),
        difficulty: q.difficulty ? String(q.difficulty) : "",
        questionType: q.questionType || "",
        tagsText: (q.tags || []).join("，"),
        version: q.version
      });
      state.existingPhotos = await Promise.all(
        (detail.attachments || []).map(async (att) => {
          try {
            const blobUrl = await fetchAuthBlobUrl(att.fileUrl);
            return Object.assign({}, att, { blobUrl });
          } catch {
            return att;
          }
        })
      );
      if (state.form.chapterId) {
        state.knowledgePoints =
          (await request("/taxonomy/knowledge-points" + queryString({ chapterId: state.form.chapterId }))) || [];
      }
    }
  } catch (err) {
    container.innerHTML = `<div class="card empty-state"><div class="empty-title">加载失败</div><p>${escapeHtml(err.message)}</p></div>`;
    return;
  }

  function render() {
    const totalPhotos = state.existingPhotos.length + state.pendingPhotos.length;
    container.innerHTML = `
      <div class="split-layout">
        <div class="stack-md">
          <section class="card">
            <div class="card-header">
              <div>
                <h3 class="card-title">AI 拍照 / 传图识题</h3>
                <p class="card-desc">最多上传 4 张图片（可同时上传题卷与答案页，系统将自动识别并按题号重排对齐）</p>
              </div>
              <span class="pill pill-blue">${totalPhotos} / 4 张</span>
            </div>

            <div class="dropzone" id="photo-dropzone">
              <input type="file" id="photo-input" accept="image/*" multiple style="display:none;" />
              <div style="color:var(--accent);margin-bottom:6px;">${icon("camera")}</div>
              <div style="font-weight:600;color:var(--text-primary);">点击选择图片，或直接将题目 / 答案照片拖到这里</div>
              <div style="font-size:12px;color:var(--text-muted);margin-top:4px;">支持 JPG / PNG / WebP，上传后自动调用后端 DeepSeek 识题接口</div>
            </div>

            ${
              state.recognitionHint
                ? `<div class="row-between" style="margin-top:12px;padding:10px 14px;border-radius:12px;background:${
                    state.recognitionFailed ? "var(--danger-bg)" : "var(--bg-accent-soft)"
                  };color:${state.recognitionFailed ? "var(--danger)" : "var(--accent)"};font-size:12.5px;">
                    <span>${escapeHtml(state.recognitionHint)}</span>
                    ${
                      state.recognitionFailed && !state.recognizing
                        ? `<button type="button" class="btn btn-sm btn-secondary" id="btn-retry-ocr">重试识别</button>`
                        : ""
                    }
                  </div>`
                : ""
            }

            ${
              totalPhotos > 0
                ? `<div class="photo-grid">
                    ${state.existingPhotos
                      .map(
                        (p) => `
                        <div class="photo-tile">
                          <img src="${escapeHtml(p.blobUrl || "")}" alt="已上传图片" data-preview="${escapeHtml(p.blobUrl || "")}" />
                          <div class="photo-badge ok">已保存附件</div>
                        </div>`
                      )
                      .join("")}
                    ${state.pendingPhotos
                      .map(
                        (p, idx) => `
                        <div class="photo-tile">
                          <img src="${escapeHtml(p.previewUrl)}" alt="待上传图片" data-preview="${escapeHtml(p.previewUrl)}" />
                          <button type="button" class="photo-remove" data-remove-photo="${idx}" title="移除">×</button>
                          <div class="photo-badge ${
                            p.status === "recognized" ? "ok" : p.status === "failed" ? "err" : "busy"
                          }">${escapeHtml(
                            p.status === "processing"
                              ? "AI 识别中…"
                              : p.message || (p.status === "recognized" ? "已识别" : "待识别")
                          )}</div>
                        </div>`
                      )
                      .join("")}
                  </div>`
                : ""
            }
          </section>

          <section class="card stack-md">
            <div class="card-header" style="margin-bottom:0;">
              <div>
                <h3 class="card-title">题干与解析内容</h3>
                <p class="card-desc">支持 Markdown 表格与多小题编号（如 1. 2. 3.）</p>
              </div>
              <div class="row-gap">
                <button type="button" class="btn btn-ghost btn-sm" id="btn-format-text">一键整理题面</button>
                <button type="button" class="btn btn-secondary btn-sm" id="btn-toggle-preview">${
                  state.previewMode ? "返回编辑" : "预览排版"
                }</button>
              </div>
            </div>

            <div class="field-group">
              <label class="field-label">题目内容 <span class="field-hint">文字与图片至少填写一项</span></label>
              ${
                state.previewMode
                  ? `<div class="answer-box" style="min-height:140px;">${
                      state.form.questionText ? toHtml(state.form.questionText) : `<span style="color:var(--text-muted);">暂无题干内容</span>`
                    }</div>`
                  : `<textarea class="textarea textarea-lg" id="f-questionText" placeholder="输入或粘贴历史题干、材料及选项…">${escapeHtml(
                      state.form.questionText
                    )}</textarea>`
              }
            </div>

            <div class="grid-2">
              <div class="field-group">
                <label class="field-label">参考答案 <span class="field-hint">多题支持简写如 BACCCC</span></label>
                <textarea class="textarea" id="f-correctAnswer" placeholder="如：B 或 1. B\n2. A">${escapeHtml(
                  state.form.correctAnswer
                )}</textarea>
              </div>
              <div class="field-group">
                <label class="field-label">我的错答 <span class="field-hint">选填</span></label>
                <textarea class="textarea" id="f-ownAnswer" placeholder="记录当时选错的选项或思路">${escapeHtml(
                  state.form.ownAnswer
                )}</textarea>
              </div>
            </div>

            <div class="field-group">
              <label class="field-label">答案解析 <span class="field-hint">选填</span></label>
              <textarea class="textarea" id="f-explanation" placeholder="填写标准解析、材料关键句与解题步骤">${escapeHtml(
                state.form.explanation
              )}</textarea>
            </div>

            <div class="field-group">
              <label class="field-label">我的订正笔记 <span class="field-hint">选填</span></label>
              <textarea class="textarea" id="f-correction" placeholder="记录自己的易错警示与订正思路">${escapeHtml(
                state.form.correction
              )}</textarea>
            </div>
          </section>
        </div>

        <aside class="stack-md">
          <section class="card stack-md">
            <h3 class="card-title">分类与标签</h3>

            <div class="field-group">
              <label class="field-label">
                <span>历史章节</span>
                <button type="button" class="btn btn-ghost btn-sm" id="btn-add-chapter" style="height:26px;padding:0 8px;">+ 新增</button>
              </label>
              <select class="select" id="f-chapter">
                <option value="">请选择历史章节</option>
                ${state.chapters
                  .map(
                    (c) =>
                      `<option value="${escapeHtml(c.id)}" ${c.id === state.form.chapterId ? "selected" : ""}>${escapeHtml(
                        c.name
                      )}</option>`
                  )
                  .join("")}
              </select>
            </div>

            <div class="field-group">
              <label class="field-label">
                <span>知识点</span>
                <button type="button" class="btn btn-ghost btn-sm" id="btn-add-kp" style="height:26px;padding:0 8px;">+ 新增</button>
              </label>
              ${
                state.form.chapterId
                  ? state.knowledgePoints.length
                    ? `<div class="row-gap" id="kp-picker">
                        ${state.knowledgePoints
                          .map((kp) => {
                            const active = state.form.knowledgePointIds.includes(kp.id);
                            return `<button type="button" class="chip-btn ${active ? "active" : ""}" data-kp-id="${escapeHtml(
                              kp.id
                            )}">${escapeHtml(kp.name)}</button>`;
                          })
                          .join("")}
                      </div>`
                    : `<div style="font-size:12px;color:var(--text-muted);">该章节暂无知识点，可点击右上角新增。</div>`
                  : `<div style="font-size:12px;color:var(--text-muted);">请先选择历史章节</div>`
              }
            </div>

            <div class="field-group">
              <label class="field-label">错因归类</label>
              <div class="row-gap" id="reason-picker">
                ${REASONS.map(
                  (r) =>
                    `<button type="button" class="chip-btn ${state.form.errorReason === r ? "active" : ""}" data-reason="${r}">${r}</button>`
                ).join("")}
              </div>
            </div>

            <div class="field-group">
              <label class="field-label">题目类型</label>
              <div class="row-gap" id="type-picker">
                ${QUESTION_TYPES.map(
                  (t) =>
                    `<button type="button" class="chip-btn ${state.form.questionType === t ? "active" : ""}" data-qtype="${t}">${t}</button>`
                ).join("")}
              </div>
            </div>

            <div class="grid-2">
              <div class="field-group">
                <label class="field-label">难度评级</label>
                <select class="select" id="f-difficulty">
                  <option value="">未评定</option>
                  <option value="1" ${state.form.difficulty === "1" ? "selected" : ""}>1 轻松</option>
                  <option value="2" ${state.form.difficulty === "2" ? "selected" : ""}>2 较简单</option>
                  <option value="3" ${state.form.difficulty === "3" ? "selected" : ""}>3 一般</option>
                  <option value="4" ${state.form.difficulty === "4" ? "selected" : ""}>4 较难</option>
                  <option value="5" ${state.form.difficulty === "5" ? "selected" : ""}>5 困难</option>
                </select>
              </div>
              <div class="field-group">
                <label class="field-label">做题日期</label>
                <input class="input" id="f-questionDate" type="date" value="${escapeHtml(state.form.questionDate)}" />
              </div>
            </div>

            <div class="field-group">
              <label class="field-label">题目来源</label>
              <input class="input" id="f-source" value="${escapeHtml(state.form.source)}" placeholder="如：期中模拟卷、课后练习" />
            </div>

            <div class="field-group">
              <label class="field-label">自定义标签 <span class="field-hint">逗号分隔</span></label>
              <input class="input" id="f-tagsText" value="${escapeHtml(state.form.tagsText)}" placeholder="如：中国近代史，史料实证" />
            </div>

            <button type="button" class="btn btn-primary" id="btn-save-question" style="width:100%;height:46px;" ${
              state.saving || state.recognizing ? "disabled" : ""
            }>
              ${state.saving ? "正在保存…" : state.recognizing ? "等待 AI 识别完成…" : editing ? "保存修改" : "保存到历史错题本"}
            </button>
          </section>
        </aside>
      </div>
    `;

    bindEvents();
  }

  function syncTextInputs() {
    ["questionText", "correctAnswer", "ownAnswer", "explanation", "correction", "source", "tagsText", "questionDate", "difficulty"].forEach(
      (field) => {
        const el = container.querySelector(`#f-${field}`);
        if (el) state.form[field] = el.value;
      }
    );
  }

  async function addFilesAndRecognize(fileList) {
    if (state.recognizing) {
      toast("请等待当前图片识别完成", "error");
      return;
    }
    const remain = Math.max(0, 4 - state.existingPhotos.length - state.pendingPhotos.length);
    const files = Array.from(fileList || []).filter((f) => f.type.startsWith("image/")).slice(0, remain);
    if (!files.length) {
      toast("一道错题最多添加 4 张图片", "error");
      return;
    }
    syncTextInputs();
    if (!state.form.source) state.form.source = "图片收录";
    const added = files.map((file) => ({
      file,
      previewUrl: URL.createObjectURL(file),
      status: "pending",
      message: "排队识别中",
      idempotencyKey: createKey()
    }));
    state.pendingPhotos = state.pendingPhotos.concat(added);
    render();
    await recognizeQueue(added);
  }

  async function recognizeQueue(targets) {
    const queue = (targets || []).filter((p) => p.status !== "recognized");
    if (!queue.length) return;
    state.recognizing = true;
    state.recognitionFailed = false;
    state.recognitionHint = "正在调用 DeepSeek 视觉模型识别题目、答案页与解析…";
    render();

    let okCount = 0;
    let failCount = 0;
    let answerMatched = 0;
    let lastAlign = null;

    for (const photo of queue) {
      photo.status = "processing";
      photo.message = "AI 识别中…";
      render();
      try {
        const res = await uploadFile("/recognition/question-image", photo.file, "file");
        if (!res || !res.recognized) {
          failCount += 1;
          photo.status = "failed";
          photo.message = (res && res.message) || "未识别到清晰内容";
          continue;
        }
        const kind = res.contentKind || "QUESTIONS";
        Object.assign(state.answerMap, res.answerMap || {});
        Object.assign(state.explanationMap, res.explanationMap || {});

        if (kind === "ANSWER_SHEET") {
          const sheetCount = Object.keys(res.answerMap || {}).length;
          lastAlign = alignRecognizedAnswers(state.form, state.originalNumbers, state.answerMap, state.explanationMap);
          if (lastAlign.matchedAnswers > 0) answerMatched += 1;
          photo.status = "recognized";
          photo.message =
            lastAlign.matchedAnswers > 0
              ? `答案页已匹配 ${lastAlign.matchedAnswers} 题`
              : `已识别答案页（${sheetCount} 条）`;
          okCount += 1;
          continue;
        }

        const rawText = String(res.questionText || res.text || "").trim();
        if (!rawText) {
          failCount += 1;
          photo.status = "failed";
          photo.message = res.message || "未识别到清晰题目";
          continue;
        }
        let formatted = formatRecognitionText(rawText);
        if (state.originalNumbers.length > 0) {
          formatted = renumberTextFrom(formatted, state.originalNumbers.length + 1);
        }
        state.form.questionText = appendRecognizedText(state.form.questionText, formatted);
        if (Array.isArray(res.originalNumbers) && res.originalNumbers.length) {
          state.originalNumbers = state.originalNumbers.concat(res.originalNumbers.map(String));
        }
        const aligned = alignRecognizedAnswers(state.form, state.originalNumbers, state.answerMap, state.explanationMap);
        lastAlign = aligned;
        if (aligned.matchedAnswers === 0) {
          if (res.correctAnswer) {
            answerMatched += 1;
            state.form.correctAnswer = appendRecognizedText(state.form.correctAnswer, res.correctAnswer);
          }
          if (res.explanation) {
            state.form.explanation = appendRecognizedText(state.form.explanation, res.explanation);
          }
        } else {
          answerMatched += 1;
        }
        if (!state.form.questionType && res.questionType) state.form.questionType = res.questionType;
        if (!state.form.tagsText.trim() && Array.isArray(res.suggestedTags) && res.suggestedTags.length) {
          state.form.tagsText = res.suggestedTags.join("，");
        }
        photo.status = "recognized";
        photo.message = state.form.correctAnswer.trim() ? "题干与答案已对齐" : "已识别题干";
        okCount += 1;
      } catch (err) {
        failCount += 1;
        photo.status = "failed";
        photo.message = err.message || "识别失败";
      }
    }

    state.recognizing = false;
    if (failCount && okCount) {
      state.recognitionFailed = true;
      state.recognitionHint = "部分图片未识别成功，可点击重试或手动补充内容。";
    } else if (failCount) {
      state.recognitionFailed = true;
      state.recognitionHint = "图片识别失败，请检查后端 DeepSeek API 配置或重试。";
    } else if (lastAlign && lastAlign.matchedAnswers > 0 && lastAlign.missingIndexes.length > 0) {
      state.recognitionHint = `已按原题号自动匹配 ${lastAlign.matchedAnswers}/${lastAlign.totalQuestions} 道答案；第 ${lastAlign.missingIndexes.join(
        "、"
      )} 题未找到对应答案，请手动补全。`;
    } else {
      state.recognitionHint = answerMatched
        ? "识别完成，题目、参考答案与解析已按 1..N 自动对齐，请核对后保存。"
        : "识别完成，未发现印刷答案；可继续上传答案页自动匹配，或直接手动填写。";
    }
    render();
  }

  function bindEvents() {
    const dropzone = container.querySelector("#photo-dropzone");
    const fileInput = container.querySelector("#photo-input");
    dropzone?.addEventListener("click", () => fileInput?.click());
    dropzone?.addEventListener("dragover", (e) => {
      e.preventDefault();
      dropzone.classList.add("dragover");
    });
    dropzone?.addEventListener("dragleave", () => dropzone.classList.remove("dragover"));
    dropzone?.addEventListener("drop", (e) => {
      e.preventDefault();
      dropzone.classList.remove("dragover");
      addFilesAndRecognize(e.dataTransfer?.files);
    });
    fileInput?.addEventListener("change", (e) => {
      addFilesAndRecognize(e.target.files);
      fileInput.value = "";
    });

    container.querySelectorAll("[data-preview]").forEach((img) => {
      img.addEventListener("click", () => openImageLightbox(img.getAttribute("data-preview")));
    });
    container.querySelectorAll("[data-remove-photo]").forEach((btn) => {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        if (state.recognizing) return toast("识别进行中，请稍候", "error");
        syncTextInputs();
        const idx = Number(btn.getAttribute("data-remove-photo"));
        state.pendingPhotos.splice(idx, 1);
        render();
      });
    });

    container.querySelector("#btn-retry-ocr")?.addEventListener("click", () => {
      syncTextInputs();
      recognizeQueue(state.pendingPhotos.filter((p) => p.status !== "recognized"));
    });

    container.querySelector("#btn-format-text")?.addEventListener("click", () => {
      syncTextInputs();
      const formatted = formatRecognitionText(state.form.questionText);
      if (!formatted || formatted === state.form.questionText.trim()) {
        toast("题面已经整理好");
        return;
      }
      state.form.questionText = formatted;
      toast("已整理题面格式", "success");
      render();
    });

    container.querySelector("#btn-toggle-preview")?.addEventListener("click", () => {
      syncTextInputs();
      state.previewMode = !state.previewMode;
      render();
    });

    container.querySelector("#f-chapter")?.addEventListener("change", async (e) => {
      syncTextInputs();
      state.form.chapterId = e.target.value;
      state.form.knowledgePointIds = [];
      state.knowledgePoints = state.form.chapterId
        ? (await request("/taxonomy/knowledge-points" + queryString({ chapterId: state.form.chapterId }))) || []
        : [];
      render();
    });

    container.querySelector("#btn-add-chapter")?.addEventListener("click", async () => {
      syncTextInputs();
      const name = await promptDialog({ title: "新增个人历史章节", placeholder: "输入章节名称，如：近代中国反侵略斗争" });
      if (!name) return;
      try {
        const chapter = await request("/taxonomy/chapters", {
          method: "POST",
          data: {
            subjectId: "history",
            grade: authState.profile?.grade || null,
            textbookVersion: authState.profile?.textbookVersion || null,
            name
          }
        });
        state.chapters.push(chapter);
        state.form.chapterId = chapter.id;
        state.form.knowledgePointIds = [];
        state.knowledgePoints = [];
        toast("章节已添加", "success");
        render();
      } catch (err) {
        toast(err.message || "添加失败", "error");
      }
    });

    container.querySelector("#btn-add-kp")?.addEventListener("click", async () => {
      syncTextInputs();
      if (!state.form.chapterId) return toast("请先选择历史章节", "error");
      const name = await promptDialog({ title: "新增个人知识点", placeholder: "输入知识点名称" });
      if (!name) return;
      try {
        const kp = await request("/taxonomy/knowledge-points", {
          method: "POST",
          data: { chapterId: state.form.chapterId, name }
        });
        state.knowledgePoints.push(kp);
        state.form.knowledgePointIds.push(kp.id);
        toast("知识点已添加", "success");
        render();
      } catch (err) {
        toast(err.message || "添加失败", "error");
      }
    });

    container.querySelector("#kp-picker")?.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-kp-id]");
      if (!btn) return;
      syncTextInputs();
      const id = btn.getAttribute("data-kp-id");
      const idx = state.form.knowledgePointIds.indexOf(id);
      if (idx >= 0) state.form.knowledgePointIds.splice(idx, 1);
      else state.form.knowledgePointIds.push(id);
      render();
    });

    container.querySelector("#reason-picker")?.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-reason]");
      if (!btn) return;
      syncTextInputs();
      const val = btn.getAttribute("data-reason");
      state.form.errorReason = state.form.errorReason === val ? "" : val;
      render();
    });

    container.querySelector("#type-picker")?.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-qtype]");
      if (!btn) return;
      syncTextInputs();
      const val = btn.getAttribute("data-qtype");
      state.form.questionType = state.form.questionType === val ? "" : val;
      render();
    });

    container.querySelector("#btn-save-question")?.addEventListener("click", async () => {
      syncTextInputs();
      const text = state.form.questionText.trim();
      if (!text && state.pendingPhotos.length === 0 && state.existingPhotos.length === 0) {
        return toast("请填写题目文字或至少上传一张图片", "error");
      }
      state.saving = true;
      render();
      try {
        const tags = state.form.tagsText
          .split(/[，,]/)
          .map((t) => t.trim())
          .filter(Boolean);
        const payload = {
          idempotencyKey: state.form.idempotencyKey,
          questionText: text || IMAGE_DRAFT_TITLE,
          ownAnswer: state.form.ownAnswer,
          correctAnswer: state.form.correctAnswer,
          correction: state.form.correction,
          explanation: state.form.explanation,
          subjectId: "history",
          chapterId: state.form.chapterId || null,
          knowledgePointIds: state.form.knowledgePointIds,
          tags,
          errorReason: state.form.errorReason,
          source: state.form.source,
          questionDate: state.form.questionDate,
          difficulty: state.form.difficulty ? Number(state.form.difficulty) : null,
          questionType: state.form.questionType,
          version: state.form.version
        };
        const saved = state.id
          ? await request(`/questions/${state.id}`, { method: "PUT", data: payload })
          : await request("/questions", { method: "POST", data: payload });

        for (const photo of state.pendingPhotos) {
          await uploadFile(`/questions/${saved.id}/attachments`, photo.file, "file", photo.idempotencyKey);
        }
        toast(editing ? "错题修改已保存" : "已保存到历史错题本", "success");
        window.location.hash = `#/question/${encodeURIComponent(saved.id)}`;
      } catch (err) {
        state.saving = false;
        toast(err.message || "保存失败", "error");
        render();
      }
    });
  }

  render();
}
