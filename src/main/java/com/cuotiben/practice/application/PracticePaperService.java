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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 历史错题专项组卷导出服务：使用纯 JDK 生成可直接在 Word / WPS / 微信中预览和打印的 .docx 试卷。
 */
@Service
public class PracticePaperService {
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

        String documentXml = buildDocumentXml(title, selected, includeAnswers);
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

    private String buildDocumentXml(String title, List<QuestionRecord> list, boolean includeAnswers) {
        StringBuilder body = new StringBuilder();
        body.append(paragraph(title, 36, true, "center", 120));
        body.append(paragraph("日期：" + LocalDate.now() + "    共 " + list.size() + " 题    姓名：____________    得分：________", 20, false, "center", 260));

        for (int i = 0; i < list.size(); i++) {
            QuestionRecord q = list.get(i);
            StringBuilder meta = new StringBuilder("第 " + (i + 1) + " 题");
            if (q.questionType() != null && !q.questionType().isBlank()) meta.append("  [").append(q.questionType()).append("]");
            if (q.chapterName() != null && !q.chapterName().isBlank()) meta.append("  ").append(q.chapterName());
            body.append(paragraph(meta.toString(), 24, true, "left", 80));

            String cleanQuestion = stripMarkdown(q.questionText());
            for (String line : cleanQuestion.split("\\r?\\n")) {
                if (!line.isBlank()) {
                    body.append(paragraph(line.trim(), 22, false, "left", 60));
                }
            }
            body.append(paragraph("作答区：", 20, false, "left", 240));
        }

        if (includeAnswers) {
            body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            body.append(paragraph("参考答案与解析", 32, true, "center", 200));
            for (int i = 0; i < list.size(); i++) {
                QuestionRecord q = list.get(i);
                body.append(paragraph("第 " + (i + 1) + " 题", 24, true, "left", 60));
                String answer = q.correctAnswer() != null && !q.correctAnswer().isBlank()
                        ? stripMarkdown(q.correctAnswer()) : "（暂未录入参考答案）";
                body.append(paragraph("【参考答案】" + answer, 22, false, "left", 60));
                if (q.correction() != null && !q.correction().isBlank()) {
                    body.append(paragraph("【订正思路】" + stripMarkdown(q.correction()), 21, false, "left", 60));
                }
                if (q.explanation() != null && !q.explanation().isBlank()) {
                    body.append(paragraph("【解析】" + stripMarkdown(q.explanation()), 21, false, "left", 60));
                }
                if (q.errorReason() != null && !q.errorReason().isBlank()) {
                    body.append(paragraph("【历史错因】" + q.errorReason(), 20, false, "left", 140));
                } else {
                    body.append(paragraph("", 20, false, "left", 100));
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

    public record PaperCommand(String title, List<String> questionIds, String chapterId,
                               String errorReason, Integer limit, Boolean includeAnswers) {}

    public record GeneratedPaper(String fileName, byte[] bytes) {}
}
