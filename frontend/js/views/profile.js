import {
  request,
  uploadFile,
  fetchAuthBlobUrl,
  authState,
  getDeviceId,
  getApiBase,
  setApiBase,
  switchAccount,
  triggerBlobDownload
} from "../api.js";
import { escapeHtml, toast, confirmDialog, promptDialog, openModal } from "../ui.js";
import { icon } from "../icons.js";

export async function renderProfile(container, ctx) {
  ctx.setHeader("个人档案与演示设置", "管理学习档案、头像、数据导出与双端演示账号配置");
  container.innerHTML = `<div class="loading-state"><div class="spinner"></div><p>正在加载个人档案…</p></div>`;

  let profile = null;
  let avatarBlobUrl = "";

  try {
    profile = await request("/me");
    authState.profile = profile;
    ctx.updateSidebarProfile(profile);
    if (profile.avatarUrl) {
      try {
        avatarBlobUrl = await fetchAuthBlobUrl(profile.avatarUrl);
      } catch {}
    }
  } catch (err) {
    container.innerHTML = `<div class="card empty-state"><div class="empty-title">加载失败</div><p>${escapeHtml(err.message)}</p></div>`;
    return;
  }

  function render() {
    const initial = (profile.nickname || "学").charAt(0);
    container.innerHTML = `
      <div class="split-layout">
        <div class="stack-md">
          <section class="card stack-md">
            <div class="row-between">
              <div class="row-gap">
                <div class="avatar-circle" style="width:64px;height:64px;font-size:24px;" id="profile-avatar-box">
                  ${avatarBlobUrl ? `<img src="${escapeHtml(avatarBlobUrl)}" alt="头像" />` : escapeHtml(initial)}
                </div>
                <div>
                  <h2 style="margin:0;font-size:18px;">${escapeHtml(profile.nickname || "网页端学生")}</h2>
                  <p style="margin:4px 0 0;font-size:12.5px;color:var(--text-muted);">
                    ${escapeHtml([profile.grade, profile.semester, profile.textbookVersion].filter(Boolean).join(" · ") || "历史专项学习者")}
                  </p>
                </div>
              </div>
              <div>
                <input type="file" id="avatar-file-input" accept="image/*" style="display:none;" />
                <button type="button" class="btn btn-secondary btn-sm" id="btn-upload-avatar">${icon("camera")} 更换头像</button>
              </div>
            </div>

            <div class="grid-2" style="padding-top:12px;border-top:1px solid var(--border-subtle);">
              <div class="field-group">
                <label class="field-label">昵称</label>
                <input class="input" id="p-nickname" value="${escapeHtml(profile.nickname || "")}" placeholder="输入你的昵称" />
              </div>
              <div class="field-group">
                <label class="field-label">年级</label>
                <input class="input" id="p-grade" value="${escapeHtml(profile.grade || "")}" placeholder="如：高一、高二、初三" />
              </div>
              <div class="field-group">
                <label class="field-label">学期</label>
                <input class="input" id="p-semester" value="${escapeHtml(profile.semester || "")}" placeholder="如：上学期、下学期" />
              </div>
              <div class="field-group">
                <label class="field-label">教材版本</label>
                <input class="input" id="p-textbook" value="${escapeHtml(profile.textbookVersion || "")}" placeholder="如：统编版 / 人教版" />
              </div>
            </div>

            <div style="display:flex;justify-content:flex-end;">
              <button type="button" class="btn btn-primary" id="btn-save-profile">保存个人档案</button>
            </div>
          </section>

          <section class="card stack-md">
            <div>
              <h3 class="card-title">双端演示与账号工具</h3>
              <p class="card-desc">网页端与微信小程序共用同一套 Spring Boot 后端与 MySQL 数据库，绑定相同设备 ID 即可跨端同步数据</p>
            </div>

            <div class="field-group">
              <label class="field-label">当前设备标识（deviceId）</label>
              <div style="display:flex;gap:8px;">
                <input class="input" id="p-device-id" value="${escapeHtml(getDeviceId())}" readonly style="background:var(--bg-pill);" />
                <button type="button" class="btn btn-secondary btn-sm" id="btn-copy-device">复制</button>
                <button type="button" class="btn btn-tonal btn-sm" id="btn-bind-device">绑定指定 ID</button>
              </div>
            </div>

            <div class="field-group">
              <label class="field-label">后端 API 地址 <span class="field-hint">默认自动使用当前服务同源地址</span></label>
              <div style="display:flex;gap:8px;">
                <input class="input" id="p-api-base" value="${escapeHtml(getApiBase())}" placeholder="http://localhost:8080/api/v1" />
                <button type="button" class="btn btn-secondary btn-sm" id="btn-save-api">保存并重连</button>
              </div>
            </div>
          </section>
        </div>

        <aside class="stack-md">
          <section class="card stack-sm">
            <h3 class="card-title" style="margin-bottom:6px;">数据与帮助</h3>
            <button type="button" class="btn btn-secondary" id="btn-export-json" style="width:100%;justify-content:flex-start;">
              ${icon("download")} 导出个人全部数据（JSON）
            </button>
            <button type="button" class="btn btn-secondary" id="btn-show-help" style="width:100%;justify-content:flex-start;">
              ${icon("book")} 查看使用说明与复习规则
            </button>
            <a class="btn btn-secondary" href="#/feedback" style="width:100%;justify-content:flex-start;">
              ${icon("message")} 提交意见反馈
            </a>
          </section>

          <section class="card stack-sm">
            <h3 class="card-title" style="margin-bottom:6px;">账号管理</h3>
            <button type="button" class="btn btn-tonal" id="btn-switch-account" style="width:100%;">
              ${icon("refresh")} 切换全新测试账号
            </button>
            <button type="button" class="btn btn-danger" id="btn-delete-account" style="width:100%;">
              ${icon("trash")} 永久删除当前账号数据
            </button>
          </section>
        </aside>
      </div>
    `;

    const avatarInput = container.querySelector("#avatar-file-input");
    container.querySelector("#btn-upload-avatar")?.addEventListener("click", () => avatarInput?.click());
    avatarInput?.addEventListener("change", async (e) => {
      const file = e.target.files?.[0];
      if (!file) return;
      try {
        profile = await uploadFile("/me/avatar", file, "file");
        authState.profile = profile;
        avatarBlobUrl = await fetchAuthBlobUrl(profile.avatarUrl);
        ctx.updateSidebarProfile(profile, avatarBlobUrl);
        toast("头像已更新", "success");
        render();
      } catch (err) {
        toast(err.message || "头像上传失败", "error");
      }
      avatarInput.value = "";
    });

    container.querySelector("#btn-save-profile")?.addEventListener("click", async () => {
      const payload = {
        nickname: container.querySelector("#p-nickname").value.trim(),
        grade: container.querySelector("#p-grade").value.trim(),
        semester: container.querySelector("#p-semester").value.trim(),
        textbookVersion: container.querySelector("#p-textbook").value.trim(),
        preferredSubjects: profile.preferredSubjects || []
      };
      try {
        profile = await request("/me", { method: "PUT", data: payload });
        authState.profile = profile;
        ctx.updateSidebarProfile(profile, avatarBlobUrl);
        toast("个人档案已保存", "success");
        render();
      } catch (err) {
        toast(err.message || "保存失败", "error");
      }
    });

    container.querySelector("#btn-copy-device")?.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(getDeviceId());
        toast("设备标识已复制", "success");
      } catch {
        toast("复制失败，请手动选中复制", "error");
      }
    });

    container.querySelector("#btn-bind-device")?.addEventListener("click", async () => {
      const nextId = await promptDialog({
        title: "绑定指定设备标识（deviceId）",
        placeholder: "粘贴小程序或其他浏览器的 deviceId",
        defaultValue: getDeviceId(),
        confirmText: "切换并同步"
      });
      if (!nextId) return;
      try {
        await switchAccount(nextId);
        toast("已切换到指定账号", "success");
        window.location.hash = "#/home";
      } catch (err) {
        toast(err.message || "切换失败", "error");
      }
    });

    container.querySelector("#btn-save-api")?.addEventListener("click", async () => {
      const val = container.querySelector("#p-api-base").value.trim();
      setApiBase(val);
      toast("接口地址已更新，正在重新连接…", "success");
      window.location.reload();
    });

    container.querySelector("#btn-export-json")?.addEventListener("click", async () => {
      try {
        const payload = await request("/me/export");
        const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json;charset=utf-8" });
        triggerBlobDownload(blob, `小E错题本-个人数据-${Date.now()}.json`);
        toast("个人数据 JSON 已开始下载", "success");
      } catch (err) {
        toast(err.message || "导出失败", "error");
      }
    });

    container.querySelector("#btn-show-help")?.addEventListener("click", () => {
      openModal({
        title: "使用说明与复习规则",
        showCancel: false,
        confirmText: "我知道了",
        bodyHtml: `
          <div class="stack-sm" style="margin-top:10px;color:var(--text-secondary);font-size:13.5px;line-height:1.7;">
            <p style="margin:0;">1. <strong>拍照 / 传图 AI 识题</strong>：支持一次上传多张题卷与答案页，系统自动调用 DeepSeek 视觉模型识别并按 1..N 重编号对齐答案。</p>
            <p style="margin:0;">2. <strong>智能练习与掌握度</strong>：选择题自动判分，历史主观材料题对照解析后按 1~5 档自评。达到 4 档及以上并连续通过 3 次后自动标记为「已通过」。</p>
            <p style="margin:0;">3. <strong>组卷中心</strong>：在错题库勾选题目即可生成并保存组卷记录，随时按需导出学生做题版、老师做题版与纯答案版 Word（.docx）试卷。</p>
          </div>
        `
      });
    });

    container.querySelector("#btn-switch-account")?.addEventListener("click", async () => {
      const ok = await confirmDialog({
        title: "切换全新测试账号",
        message: "将为你创建一个新的测试身份，旧账号数据仍安全保留在 MySQL 数据库中。",
        confirmText: "立即切换"
      });
      if (!ok) return;
      try {
        await switchAccount();
        toast("已切换全新测试账号", "success");
        window.location.hash = "#/home";
      } catch (err) {
        toast(err.message || "切换失败", "error");
      }
    });

    container.querySelector("#btn-delete-account")?.addEventListener("click", async () => {
      const ok = await confirmDialog({
        title: "永久删除账号数据",
        message: "此操作会永久删除当前账号下的所有历史错题、图片与复习记录，无法撤销。确定继续吗？",
        confirmText: "确认永久删除",
        danger: true
      });
      if (!ok) return;
      try {
        await request("/me", { method: "DELETE" });
        await switchAccount();
        toast("原账号数据已删除，已为你重新初始化账号", "success");
        window.location.hash = "#/home";
      } catch (err) {
        toast(err.message || "删除失败", "error");
      }
    });
  }

  render();
}
