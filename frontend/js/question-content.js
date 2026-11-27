const ANSWER_LABELS = "答案解析|解题思路|正确答案|参考答案|标准答案|答案|解析";

function questionMarker(line) {
  const value = String(line || "").trim();
  const match = value.match(/^(?:\*\*)?(?:第\s*)?(\d{1,3})\s*(?:题)?[.、．:：)）](?:\*\*)?\s*(.*)$/);
  return match ? { number: match[1], text: match[2] || "" } : null;
}

function unwrapLabels(line) {
  let value = String(line || "").trim();
  const label = "(?:✅\\s*)?(?:" + ANSWER_LABELS + ")";
  value = value.replace(new RegExp("(?:\\*\\*|__|\\*)\\s*(" + label + "\\s*[:：=]?)\\s*(?:\\*\\*|__|\\*)", "g"), (match, text, offset, source) => {
    const previous = offset > 0 ? source.charAt(offset - 1) : "";
    return previous && !/[\s，,。；;？！?.、]/.test(previous) ? " " + text : text;
  });
  value = value.replace(new RegExp("[【\\[（(]\\s*(" + label + ")\\s*[】\\]）)]\\s*([:：=]?)", "g"), (match, text, separator, offset, source) => {
    const previous = offset > 0 ? source.charAt(offset - 1) : "";
    const prefix = previous && !/[\s，,。；;？！?.、]/.test(previous) ? " " : "";
    return prefix + text + (separator || "：");
  });
  value = value.replace(/^#{1,6}\s*/, "").replace(/^>\s?/, "").replace(/^[-+*]\s+/, "");
  return value;
}

function normalizeLeadingLabel(line) {
  let value = unwrapLabels(line);
  value = value.replace(/^(?:\*\*)?\d{1,3}\s*[.、．)）](?:\*\*)?\s+(?=(?:✅\s*)?(?:正确答案|参考答案|标准答案|答案解析|解题思路|答案|解析)(?:\s*[:：=]|(?:是|为)(?!什么|如何|怎样|为什么|是否|哪|几|谁)|\s+|$))/, "");
  const match = value.match(new RegExp("^((?:✅\\s*)?(?:" + ANSWER_LABELS + "))([\\s\\S]*)$"));
  if (!match) return value;
  const label = match[1].replace(/\s+/g, "").replace(/^✅/, "✅ ");
  const rest = match[2] || "";
  const separator = rest.match(/^\s*([:：=])\s*/);
  if (separator) return label + "：" + rest.slice(separator[0].length).trim();
  const copula = rest.match(/^\s*(是|为)\s*/);
  if (copula) {
    const content = rest.slice(copula[0].length).trim().replace(/^[:：=]\s*/, "");
    if (!/^(?:什么|如何|怎样|为什么|是否|哪|几|谁)/.test(content)) return label + "：" + content;
    return value;
  }
  if (!rest.trim()) return label + "：";
  if (/^\s+/.test(rest) && !/^(?:什么|如何|怎样|为什么|是否|哪|几|谁)/.test(rest.trim())) {
    return label + "：" + rest.trim();
  }
  return value;
}

function splitInlineLabels(line) {
  const value = normalizeLeadingLabel(line);
  const pattern = new RegExp("(^|[\\s，,。；;？！?.、])((?:✅\\s*)?(?:" + ANSWER_LABELS + "))\\s*[:：=]\\s*", "g");
  const markers = [];
  let match;
  while ((match = pattern.exec(value))) {
    markers.push({
      index: match.index,
      labelStart: match.index + match[1].length,
      bodyStart: pattern.lastIndex,
      label: match[2]
    });
  }
  if (!markers.length) return [value];
  const pieces = [];
  if (markers[0].labelStart > 0) {
    const prefix = value.slice(0, markers[0].labelStart).trim();
    if (prefix) pieces.push(prefix);
  }
  markers.forEach((marker, index) => {
    const end = index + 1 < markers.length ? markers[index + 1].labelStart : value.length;
    const body = value.slice(marker.bodyStart, end).trim();
    const label = marker.label.replace(/\s+/g, "");
    pieces.push(label + "：" + body);
  });
  return pieces;
}

function splitQuestionBlocks(source) {
  const lines = String(source || "").replace(/\r\n?/g, "\n").split("\n");
  const lead = [];
  const blocks = [];
  let current = null;
  lines.forEach((line) => {
    const value = line.trim();
    if (!value) {
      if (current) current.lines.push("");
      else if (lead.length && lead[lead.length - 1] !== "") lead.push("");
      return;
    }
    const marker = questionMarker(value);
    const numberedAnswer =
      marker &&
      /^(?:✅\s*)?(?:答案解析|解题思路|正确答案|参考答案|标准答案|答案|解析)(?:\s*[:：=]|(?:是|为)(?!什么|如何|怎样|为什么|是否|哪|几|谁)|\s+(?:[A-DＡ-Ｄ]|\S))/.test(
        marker.text
      );
    if (marker && !numberedAnswer) {
      if (current) blocks.push(current);
      current = { number: marker.number, lines: [marker.text] };
    } else if (current) {
      current.lines.push(value);
    } else {
      lead.push(value);
    }
  });
  if (current) blocks.push(current);
  if (!blocks.length) return [{ number: "1", lines: lead }];
  if (lead.length) blocks[0].lines = lead.concat(blocks[0].lines);
  return blocks;
}

function splitInlineOptions(line) {
  const value = String(line || "");
  const pattern = /(?:^|\s)([A-DＡ-Ｄ])[.、．)）]\s*/g;
  const markers = [];
  let match;
  while ((match = pattern.exec(value))) {
    markers.push({ index: match.index + (match[0].charAt(0) === " " ? 1 : 0), key: match[1] });
  }
  if (!markers.length || (markers.length === 1 && markers[0].index !== 0)) return [value];
  const chunks = [];
  if (markers[0].index > 0) chunks.push(value.slice(0, markers[0].index).trim());
  markers.forEach((marker, index) => {
    const end = index + 1 < markers.length ? markers[index + 1].index : value.length;
    chunks.push(value.slice(marker.index, end).trim());
  });
  return chunks.filter(Boolean);
}

function structuralLines(lines) {
  const expanded = [];
  lines.forEach((line) => {
    const value = unwrapLabels(line);
    if (!value) return;
    splitInlineLabels(value).forEach((piece) => {
      const plain = piece.replace(/\*\*/g, "").trim();
      if (/^(?:✅\s*)?(?:答案解析|解题思路|正确答案|参考答案|标准答案|答案|解析)\s*[:：=]/.test(plain)) {
        expanded.push(piece);
      } else {
        splitInlineOptions(piece).forEach((part) => expanded.push(part));
      }
    });
  });
  return expanded;
}

function optionMatch(line) {
  const match = String(line || "").replace(/\*\*/g, "").trim().match(/^([A-DＡ-Ｄ])[.、．)）]\s*(.*)$/);
  if (!match) return null;
  const asciiKey = match[1].replace(/[Ａ-Ｄ]/, (letter) => String.fromCharCode(letter.charCodeAt(0) - 65248));
  return { key: asciiKey, text: match[2] || "" };
}

function numberedSegments(source, expectedNumbers) {
  const result = {};
  const text = String(source || "").replace(/\r\n?/g, "\n").trim();
  if (!text) return result;
  if (Array.isArray(expectedNumbers) && expectedNumbers.length > 1) {
    const starts = [];
    const bodyStarts = [];
    let searchFrom = 0;
    let matched = 0;
    expectedNumbers.forEach((num, idx) => {
      const escaped = String(num).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      const regex = new RegExp("(?:^|[\\s。；;！!？?,，])(?:第\\s*)?" + escaped + "\\s*(?:题)?\\s*[.、．:：)）-]\\s*(?:答案\\s*[:：]\\s*)?", "g");
      regex.lastIndex = searchFrom;
      const match = regex.exec(text);
      if (match) {
        starts[idx] = match.index;
        bodyStarts[idx] = regex.lastIndex;
        searchFrom = regex.lastIndex;
        matched += 1;
      } else {
        starts[idx] = -1;
        bodyStarts[idx] = -1;
      }
    });
    if (matched > 0) {
      expectedNumbers.forEach((num, idx) => {
        if (bodyStarts[idx] < 0) return;
        let end = text.length;
        for (let j = idx + 1; j < expectedNumbers.length; j += 1) {
          if (starts[j] >= 0) {
            end = starts[j];
            break;
          }
        }
        result[String(num)] = text.slice(bodyStarts[idx], end).trim();
      });
      return result;
    }
  }
  text.split(/\n/).forEach((line) => {
    const match = line.trim().match(/^(?:第\s*)?(\d{1,3})\s*(?:题)?\s*[.、．:：)）-]\s*(?:答案\s*[:：]\s*)?(.+)$/);
    if (match) result[match[1]] = match[2].trim();
  });
  return result;
}

function parseCompactChoiceLetters(source, expectedCount) {
  if (!expectedCount || expectedCount < 2) return null;
  const cleaned = String(source || "")
    .replace(/^(?:✅\s*)?(?:正确答案|参考答案|标准答案|答案)\s*[:：]\s*/, "")
    .replace(/[\s,，、;；|/\-]+/g, "")
    .toUpperCase()
    .replace(/[Ａ-Ｄ]/g, (ch) => String.fromCharCode(ch.charCodeAt(0) - 65248));
  if (/^[A-D]{2,}$/.test(cleaned)) return cleaned.split("");
  return null;
}

function findAnswerKey(answerText, options) {
  const candidate = String(answerText || "").trim().replace(/^(?:✅\s*)?(?:正确答案|参考答案|标准答案|答案)\s*[:：]\s*/, "");
  const letterMatch = candidate.match(/^(?:选项\s*)?([A-DＡ-Ｄ])(?:\b|[.、．)）:：。！？?\s]|$)/);
  if (letterMatch) {
    return letterMatch[1].replace(/[Ａ-Ｄ]/, (letter) => String.fromCharCode(letter.charCodeAt(0) - 65248));
  }
  const answerIndex = (options || []).findIndex(
    (option) => candidate === option.text || (option.text && candidate.includes(option.text))
  );
  return answerIndex >= 0 ? options[answerIndex].key : "";
}

function extractPart(block, fallback) {
  const prompt = [];
  const options = [];
  const answerLines = [];
  const explanationLines = [];
  let mode = "prompt";
  structuralLines(block.lines).forEach((rawLine) => {
    const line = String(rawLine || "").trim();
    if (!line) return;
    const plain = line.replace(/^#{1,6}\s*/, "").replace(/\*\*/g, "").trim();
    const answerLabel = plain.match(/^(?:✅\s*)?(?:正确答案|参考答案|标准答案|答案)(?:\s*[:：]\s*(.*))?$/);
    const explanationLabel = plain.match(/^(?:答案解析|解析|解题思路)(?:\s*[:：]\s*(.*))?$/);
    if (answerLabel) {
      mode = "answer";
      if (answerLabel[1]) answerLines.push(answerLabel[1]);
      return;
    }
    if (explanationLabel) {
      mode = "explanation";
      if (explanationLabel[1]) explanationLines.push(explanationLabel[1]);
      return;
    }
    const option = optionMatch(line);
    if (option) {
      options.push(option);
      mode = "options";
      return;
    }
    if (mode === "answer") answerLines.push(plain);
    else if (mode === "explanation") explanationLines.push(line);
    else prompt.push(line);
  });

  const questionText = prompt
    .filter(Boolean)
    .join("\n\n")
    .replace(/^(?:\*\*)?(?:第\s*)?\d{1,3}\s*(?:题)?[.、．:：)）](?:\*\*)?\s*/, "")
    .trim();
  const parsedAnswer = answerLines.join(" ").trim();
  const rowAnswer = String((fallback && fallback.correctAnswer) || "").trim();
  const answerText = parsedAnswer || rowAnswer;
  const answerKey = findAnswerKey(answerText, options);
  const explanation =
    String((fallback && (fallback.explanation || fallback.correction)) || "").trim() ||
    explanationLines.join("\n\n").trim();

  return {
    number: block.number,
    questionText,
    options,
    answerText,
    answerKey,
    explanation,
    errorReason: (fallback && fallback.errorReason) || "",
    questionType: (fallback && fallback.questionType) || "",
    selectedKey: "",
    isCorrect: null,
    masteryRating: 0,
    studentAnswer: "",
    answerRevealed: false,
    studentReflection: "",
    reflectionSaved: false
  };
}

export function parsePracticeQuestion(question) {
  const value = question || {};
  const blocks = splitQuestionBlocks(value.questionText || "");
  const expectedNumbers = blocks.map((b) => b.number).filter(Boolean);
  const answersByNumber = numberedSegments(value.correctAnswer, expectedNumbers);
  const explanationsByNumber = numberedSegments(value.explanation, expectedNumbers);
  let result = blocks
    .map((block) => {
      const isSingle = blocks.length === 1;
      const fallback = isSingle
        ? value
        : { errorReason: value.errorReason || "", questionType: value.questionType || "" };
      return extractPart(block, fallback);
    })
    .filter((part) => part.questionText || part.options.length || part.answerText);
  if (!result.length) {
    result = [extractPart({ number: "1", lines: [String(value.questionText || "")] }, value)];
  }
  const compactLetters =
    result.length > 1 && Object.keys(answersByNumber).length === 0
      ? parseCompactChoiceLetters(value.correctAnswer, result.length)
      : null;

  return result.map((part, index) => {
    const originalNumber = part.number || String(index + 1);
    part.number = String(index + 1);
    if (!part.explanation && result.length === 1 && value.explanation) {
      part.explanation = value.explanation;
    }
    if (!part.explanation && result.length > 1 && explanationsByNumber[originalNumber]) {
      part.explanation = explanationsByNumber[originalNumber];
    }
    if (!part.answerText && result.length === 1 && value.correctAnswer) {
      part.answerText = answersByNumber[originalNumber] || value.correctAnswer;
      part.answerKey = findAnswerKey(part.answerText, part.options);
    }
    if (!part.answerText && result.length > 1 && answersByNumber[originalNumber]) {
      part.answerText = answersByNumber[originalNumber];
      part.answerKey = findAnswerKey(part.answerText, part.options);
    }
    if (!part.answerText && compactLetters && compactLetters[index]) {
      part.answerText = compactLetters[index];
      part.answerKey = findAnswerKey(part.answerText, part.options);
    }
    part.isMultipleChoice = part.options.length > 0;
    part.hasAnswer = !!part.answerKey || !!part.answerText;
    return part;
  });
}
