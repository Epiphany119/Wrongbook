package com.cuotiben.practice.application;

import com.cuotiben.common.ApiException;
import com.cuotiben.question.domain.QuestionRecord;
import com.cuotiben.question.infrastructure.QuestionRepository;
import com.cuotiben.question.infrastructure.QuestionRepository.QuestionFilter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 历史错题专项组卷导出服务：
 * 自动将包含多道小题的拍照错题拆分为独立题目，并按 1..N 全局连续编号，
 * 同时确保参考答案与解析与每道小题严格一一对应。
 */
@Service
public class PracticePaperService {
    private static final Pattern QUESTION_LINE_MARKER = Pattern.compile(
            "^(?:\\*\\*)?(?:第\\s*)?(\\d{1,3})\\s*(?:题)?[.、．:：)）](?:\\*\\*)?\\s*(.*)$");
    private static final Pattern EMBEDDED_ANSWER_HEAD = Pattern.compile(
            "^(?:✅\\s*)?(?:答案解析|解题思路|正确答案|参考答案|标准答案|答案|解析)(?:\\s*[:：=]|(?:是|为)(?!什么|如何|怎样|为什么|是否|哪|几|谁)|\\s+)");
    private static final Pattern OPTION_LINE = Pattern.compile("^(?:\\*\\*)?[A-DＡ-Ｄ][.、．)）]\\s*.*$");

    private static final String CONTENT_TYPES_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
            """;

    private static final String RELS_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
            """;

    private final QuestionRepository questions;

    public PracticePaperService(QuestionRepository questions) {
        this.questions = questions;
    }

    public GeneratedPaper exportDocx(String userId, PaperCommand command) {
        String title = command != null && command.title() != null && !command.title().isBlank()
                ? command.title().trim()
                : "历史错题专项练习卷";
        if (title.length() > 60) title = title.substring(0, 60);
        boolean includeAnswers = command == null || command.includeAnswers() == null || command.includeAnswers();

        List<QuestionRecord> selected = resolveQuestions(userId, command);
        if (selected.isEmpty()) {
            throw ApiException.badRequest("没有找到可用于组卷的历史错题。");
        }

        List<PaperItem> flattenedItems = flattenQuestions(selected);
        if (flattenedItems.isEmpty()) {
            throw ApiException.badRequest("没有找到可用于组卷的历史错题。");
        }

        String documentXml = buildDocumentXml(title, flattenedItems, includeAnswers);
        byte[] bytes = packageDocx(documentXml);
        String fileName = "历史错题专项卷_" + LocalDate.now() + ".docx";
        return new GeneratedPaper(fileName, bytes);
    }

    private List<QuestionRecord> resolveQuestions(String userId, PaperCommand command) {
        if (command != null && command.questionIds() != null && !command.questionIds().isEmpty()) {
            List<QuestionRecord> list = new ArrayList<>();
            for (String id : command.questionIds().stream().distinct().limit(50).toList()) {
                if (id == null || id.isBlank()) continue;
                QuestionRecord record = questions.findOwned(id.trim(), userId);
                if (record != null) list.add(record);
            }
            return list;
        }
        int limit = command == null || command.limit() == null ? 15 : Math.max(1, Math.min(50, command.limit()));
        String chapterId = command == null ? null : command.chapterId();
        String errorReason = command == null ? null : command.errorReason();
        QuestionFilter filter = new QuestionFilter(null, "history", chapterId, null, null, null, null, errorReason, null, null, null);
        return questions.list(userId, filter, 1, limit, "DUE_SOON");
    }

    private List<PaperItem> flattenQuestions(List<QuestionRecord> records) {
        List<PaperItem> result = new ArrayList<>();
        for (QuestionRecord record : records) {
            List<RawBlock> blocks = splitQuestionBlocks(stripMarkdown(record.questionText()));
            List<String> expectedNumbers = blocks.stream()
                    .map(RawBlock::originalNumber)
                    .filter(num -> num != null && !num.isBlank())
                    .toList();

            List<String> splitAnswers = blocks.size() > 1
                    ? splitMultiChoiceAnswers(stripMarkdown(record.correctAnswer()), expectedNumbers, blocks.size())
                    : null;
            List<String> splitExplanations = blocks.size() > 1
                    ? extractByExpectedNumbers(stripMarkdown(record.explanation()), expectedNumbers)
                    : null;
            List<String> splitCorrections = blocks.size() > 1
                    ? extractByExpectedNumbers(stripMarkdown(record.correction()), expectedNumbers)
                    : null;

            for (int i = 0; i < blocks.size(); i++) {
                RawBlock block = blocks.get(i);
                List<String> lines = block.lines().stream().filter(line -> !line.isBlank()).toList();
                if (lines.isEmpty()) continue;

                boolean hasOptions = lines.stream().anyMatch(line -> OPTION_LINE.matcher(line).matches());
                String answer;
                if (blocks.size() == 1) {
                    answer = stripLeadingNumber(stripMarkdown(record.correctAnswer()), block.originalNumber());
                } else {
                    answer = splitAnswers != null && i < splitAnswers.size() ? splitAnswers.get(i) : "";
                }

                String explanation;
                if (blocks.size() == 1) {
                    explanation = stripLeadingNumber(stripMarkdown(record.explanation()), block.originalNumber());
                } else {
                    explanation = splitExplanations != null && i < splitExplanations.size() ? splitExplanations.get(i) : "";
                }

                String correction;
                if (blocks.size() == 1) {
                    correction = stripLeadingNumber(stripMarkdown(record.correction()), block.originalNumber());
                } else {
                    correction = splitCorrections != null && i < splitCorrections.size() ? splitCorrections.get(i) : "";
                }

                result.add(new PaperItem(
                        lines,
                        hasOptions,
                        answer,
                        explanation,
                        correction,
                        record.errorReason(),
                        record.questionType(),
                        record.chapterName()
                ));
            }
        }
        return result;
    }

    private List<RawBlock> splitQuestionBlocks(String source) {
        String[] rawLines = source.replace('\r', '\n').split("\\n");
        List<String> lead = new ArrayList<>();
        List<RawBlock> blocks = new ArrayList<>();
        String currentNum = null;
        List<String> currentLines = null;

        for (String raw : rawLines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher matcher = QUESTION_LINE_MARKER.matcher(line);
            if (matcher.matches() && !EMBEDDED_ANSWER_HEAD.matcher(matcher.group(2)).find()) {
                if (currentLines != null) {
                    blocks.add(new RawBlock(currentNum, currentLines));
                }
                currentNum = matcher.group(1);
                currentLines = new ArrayList<>();
                String stem = matcher.group(2).trim();
                if (!stem.isEmpty()) currentLines.add(stem);
            } else if (currentLines != null) {
                currentLines.add(line);
            } else {
                lead.add(line);
            }
        }
        if (currentLines != null) {
            blocks.add(new RawBlock(currentNum, currentLines));
        }
        if (blocks.isEmpty()) {
            return List.of(new RawBlock(null, lead));
        }
        if (!lead.isEmpty()) {
            List<String> merged = new ArrayList<>(lead);
            merged.addAll(blocks.get(0).lines());
            blocks.set(0, new RawBlock(blocks.get(0).originalNumber(), merged));
        }
        return blocks;
    }

    private List<String> splitMultiChoiceAnswers(String source, List<String> expectedNumbers, int expectedCount) {
        List<String> byNumber = extractByExpectedNumbers(source, expectedNumbers);
        if (byNumber != null) return byNumber;
        if (source == null || source.isBlank() || expectedCount < 2) return null;
        String cleaned = source.trim()
                .replaceFirst("^(?:✅\\s*)?(?:正确答案|参考答案|标准答案|答案)\\s*[:：]\\s*", "")
                .replaceAll("[\\s,，、;；|/\\-]+", "")
                .toUpperCase()
                .replace('Ａ', 'A').replace('Ｂ', 'B').replace('Ｃ', 'C').replace('Ｄ', 'D');
        if (cleaned.matches("^[A-D]{2,}$")) {
            List<String> letters = new ArrayList<>(expectedCount);
            for (int i = 0; i < expectedCount; i++) {
                letters.add(i < cleaned.length() ? String.valueOf(cleaned.charAt(i)) : "");
            }
            return letters;
        }
        return null;
    }

    private List<String> extractByExpectedNumbers(String source, List<String> expectedNumbers) {
        if (source == null || source.isBlank() || expectedNumbers.isEmpty()) return null;
        String text = source.replace('\r', '\n').trim();
        int[] starts = new int[expectedNumbers.size()];
        int[] bodyStarts = new int[expectedNumbers.size()];
        int searchFrom = 0;
        int matchedCount = 0;
        for (int i = 0; i < expectedNumbers.size(); i++) {
            String num = Pattern.quote(expectedNumbers.get(i));
            Pattern p = Pattern.compile("(?:^|[\\s。；;！!？?,，])(?:第\\s*)?" + num + "\\s*(?:题)?\\s*[.、．:：)）]\\s*");
            Matcher m = p.matcher(text);
            if (m.find(searchFrom)) {
                starts[i] = m.start();
                bodyStarts[i] = m.end();
                searchFrom = m.end();
                matchedCount++;
            } else {
                starts[i] = -1;
                bodyStarts[i] = -1;
            }
        }
        if (matchedCount == 0) return null;
        List<String> result = new ArrayList<>(expectedNumbers.size());
        for (int i = 0; i < expectedNumbers.size(); i++) {
            if (bodyStarts[i] < 0) {
                result.add("");
                continue;
            }
            int end = text.length();
            for (int j = i + 1; j < expectedNumbers.size(); j++) {
                if (starts[j] >= 0) {
                    end = starts[j];
                    break;
                }
            }
            result.add(text.substring(bodyStarts[i], end).trim());
        }
        return result;
    }

    private String stripLeadingNumber(String text, String originalNumber) {
        if (text == null || text.isBlank()) return "";
        if (originalNumber == null || originalNumber.isBlank()) return text.trim();
        String num = Pattern.quote(originalNumber);
        return text.trim().replaceFirst("^(?:第\\s*)?" + num + "\\s*(?:题)?\\s*[.、．:：)）]\\s*", "").trim();
    }

    private String buildDocumentXml(String title, List<PaperItem> items, boolean includeAnswers) {
        StringBuilder body = new StringBuilder();
        body.append(paragraph(title, 36, true, "center", 120));
        body.append(paragraph("日期：" + LocalDate.now() + "    共 " + items.size() + " 题    姓名：____________    得分：________", 20, false, "center", 260));

        for (int i = 0; i < items.size(); i++) {
            PaperItem item = items.get(i);
            int globalNum = i + 1;
            List<String> lines = item.lines();
            body.append(paragraph(globalNum + ". " + lines.get(0), 22, false, "left", 60));
            for (int j = 1; j < lines.size(); j++) {
                body.append(paragraph(lines.get(j), 21, false, "left", j == lines.size() - 1 ? 140 : 40));
            }
            if (!item.hasOptions()) {
                body.append(paragraph("作答区：", 20, false, "left", 240));
            } else if (lines.size() == 1) {
                body.append(paragraph("", 20, false, "left", 100));
            }
        }

        if (includeAnswers) {
            body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            body.append(paragraph("参考答案与解析", 32, true, "center", 200));
            for (int i = 0; i < items.size(); i++) {
                PaperItem item = items.get(i);
                int globalNum = i + 1;
                String answer = item.answer() != null && !item.answer().isBlank()
                        ? item.answer() : "（暂未录入参考答案）";
                body.append(paragraph(globalNum + ". 【参考答案】" + answer, 22, true, "left", 50));
                if (item.correction() != null && !item.correction().isBlank()) {
                    body.append(paragraph("    【订正思路】" + item.correction(), 21, false, "left", 50));
                }
                if (item.explanation() != null && !item.explanation().isBlank()) {
                    body.append(paragraph("    【解析】" + item.explanation(), 21, false, "left", 110));
                } else {
                    body.append(paragraph("", 18, false, "left", 60));
                }
            }
        }

        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                """ + body + """
                    <w:sectPr>
                      <w:pgSz w:w="11906" w:h="16838"/>
                      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
                    </w:sectPr>
                  </w:body>
                </w:document>
                """;
    }

    private String paragraph(String text, int halfPointSize, boolean bold, String align, int spaceAfter) {
        String escaped = escapeXml(text == null ? "" : text);
        String boldTag = bold ? "<w:b/>" : "";
        return "<w:p>"
                + "<w:pPr><w:jc w:val=\"" + align + "\"/><w:spacing w:after=\"" + spaceAfter + "\" w:line=\"340\" w:lineRule=\"auto\"/></w:pPr>"
                + "<w:r><w:rPr><w:rFonts w:ascii=\"Microsoft YaHei\" w:eastAsia=\"Microsoft YaHei\" w:hAnsi=\"Microsoft YaHei\"/>"
                + boldTag + "<w:sz w:val=\"" + halfPointSize + "\"/><w:szCs w:val=\"" + halfPointSize + "\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + escaped + "</w:t></w:r></w:p>";
    }

    private String stripMarkdown(String text) {
        if (text == null) return "";
        return text.replaceAll("(?m)^#{1,6}\\s*", "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .trim();
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private byte[] packageDocx(String documentXml) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeEntry(zip, "[Content_Types].xml", CONTENT_TYPES_XML);
            writeEntry(zip, "_rels/.rels", RELS_XML);
            writeEntry(zip, "word/document.xml", documentXml);
            zip.finish();
            return out.toByteArray();
        } catch (IOException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PAPER_EXPORT_FAILED", "试卷生成失败，请稍后重试。");
        }
    }

    private void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private record RawBlock(String originalNumber, List<String> lines) {}

    private record PaperItem(List<String> lines, boolean hasOptions, String answer, String explanation,
                             String correction, String errorReason, String questionType, String chapterName) {}

    public record PaperCommand(String title, List<String> questionIds, String chapterId,
                               String errorReason, Integer limit, Boolean includeAnswers) {}

    public record GeneratedPaper(String fileName, byte[] bytes) {}
}
