import { escapeHtml } from "./ui.js";

export function toPlainText(source) {
  return String(source || "")
    .replace(/\r\n?/g, "\n")
    .replace(/```[\s\S]*?```/g, (m) => m.replace(/```/g, ""))
    .replace(/^#{1,6}\s+/gm, "")
    .replace(/^>\s?/gm, "")
    .replace(/^\s*[-*+]\s+/gm, "")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/__([^_]+)__/g, "$1")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    .replace(/\n{2,}/g, " ")
    .replace(/\n/g, " ")
    .replace(/\s{2,}/g, " ")
    .trim();
}

export function formatRecognitionText(source) {
  const lines = String(source || "")
    .replace(/\r\n?/g, "\n")
    .split("\n")
    .map((l) => l.trim())
    .filter((l, idx, arr) => l !== "" || (idx > 0 && arr[idx - 1] !== ""));
  const out = [];
  lines.forEach((line) => {
    if (!line) {
      out.push("");
      return;
    }
    const splitOpts = line.replace(/\s+([A-DＡ-Ｄ][.、．)）]\s*)/g, "\n$1").split("\n");
    splitOpts.forEach((chunk) => out.push(chunk.trim()));
  });
  return out.join("\n").replace(/\n{3,}/g, "\n\n").trim();
}

function renderInline(text) {
  let html = escapeHtml(text);
  html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
  html = html.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  html = html.replace(/__([^_]+)__/g, "<strong>$1</strong>");
  html = html.replace(/\*([^*]+)\*/g, "<em>$1</em>");
  return html;
}

export function toHtml(source) {
  const raw = String(source || "").replace(/\r\n?/g, "\n").trim();
  if (!raw) return "";
  const lines = raw.split("\n");
  const blocks = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i].trim();
    if (!line) {
      i += 1;
      continue;
    }
    if (line.startsWith("|") && i + 1 < lines.length && /^\|?[\s:-]+\|/.test(lines[i + 1].trim())) {
      const rows = [];
      while (i < lines.length && lines[i].trim().includes("|")) {
        rows.push(lines[i].trim());
        i += 1;
      }
      const parseCells = (row) =>
        row
          .replace(/^\|/, "")
          .replace(/\|$/, "")
          .split("|")
          .map((c) => renderInline(c.trim()));
      const header = parseCells(rows[0]);
      const bodyRows = rows.slice(2).map(parseCells);
      blocks.push(
        `<table><thead><tr>${header.map((h) => `<th>${h}</th>`).join("")}</tr></thead><tbody>${bodyRows
          .map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`)
          .join("")}</tbody></table>`
      );
      continue;
    }
    if (/^>\s?/.test(line)) {
      const quotes = [];
      while (i < lines.length && /^>\s?/.test(lines[i].trim())) {
        quotes.push(renderInline(lines[i].trim().replace(/^>\s?/, "")));
        i += 1;
      }
      blocks.push(`<blockquote>${quotes.join("<br/>")}</blockquote>`);
      continue;
    }
    if (/^[-*+]\s+/.test(line)) {
      const items = [];
      while (i < lines.length && /^[-*+]\s+/.test(lines[i].trim())) {
        items.push(`<li>${renderInline(lines[i].trim().replace(/^[-*+]\s+/, ""))}</li>`);
        i += 1;
      }
      blocks.push(`<ul>${items.join("")}</ul>`);
      continue;
    }
    const heading = line.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      const level = Math.min(4, heading[1].length + 1);
      blocks.push(`<h${level} style="margin:8px 0 6px;font-size:${17 - level}px;">${renderInline(heading[2])}</h${level}>`);
      i += 1;
      continue;
    }
    const para = [];
    while (
      i < lines.length &&
      lines[i].trim() !== "" &&
      !/^#{1,4}\s+/.test(lines[i].trim()) &&
      !/^>\s?/.test(lines[i].trim()) &&
      !/^[-*+]\s+/.test(lines[i].trim())
    ) {
      para.push(renderInline(lines[i].trim()));
      i += 1;
    }
    blocks.push(`<p>${para.join("<br/>")}</p>`);
  }
  return `<div class="md-content">${blocks.join("")}</div>`;
}
