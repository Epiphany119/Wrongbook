package com.cuotiben.practice.application;

import com.cuotiben.common.ApiException;
import com.cuotiben.common.JsonCodec;
import com.cuotiben.question.domain.QuestionRecord;
import com.cuotiben.question.infrastructure.QuestionRepository;
import com.cuotiben.question.infrastructure.QuestionRepository.QuestionFilter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 历史错题专项组卷与记录管理服务：
 * 1. 仅持久化轻量组卷状态（名称、关联错题 ID、初始版本、已使用版本），不在服务端堆积真实 Word 文件；
 * 2. 查看/导出时按需拆平多小题并全局 1..N 连续编号，在内存中实时复原 .docx；
 * 3. 支持三种版本：QUESTIONS_ONLY（学生做题版）、WITH_ANSWERS（老师做题版）、ANSWERS_ONLY（纯答案版）。
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
    private final JdbcTemplate jdbc;
    private final JsonCodec json;

    public PracticePaperService(QuestionRepository questions, JdbcTemplate jdbc, JsonCodec json) {
        this.questions = questions;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> createRecord(String userId, SavePaperCommand command) {
        if (command == null || command.questionIds() == null || command.questionIds().isEmpty()) {
            throw ApiException.badRequest("请先勾选至少一道历史错题。");
        }
        ExportMode initialMode = parseRequiredMode(command.mode());
        String title = normalizeTitle(command.title());
        List<String> validIds = new ArrayList<>();
        List<QuestionRecord> selected = new ArrayList<>();
        for (String id : command.questionIds().stream().distinct().limit(50).toList()) {
            if (id == null || id.isBlank()) continue;
            QuestionRecord record = questions.findOwned(id.trim(), userId);
            if (record != null) {
                validIds.add(record.id());
                selected.add(record);
            }
        }
        if (selected.isEmpty()) {
            throw ApiException.badRequest("所选错题不存在或已移入回收站。");
        }
        List<PaperItem> flattened = flattenQuestions(selected);
        String targetKey = canonicalQuestionKey(validIds);

        // 若同一组错题已存在组卷记录，则融合为同一条记录并追加版本标签，不产生重复记录
        List<Map<String, Object>> existingRows = jdbc.queryForList("""
                SELECT id, title, question_ids AS questionIds, default_mode AS defaultMode, used_modes AS usedModes
                FROM practice_papers WHERE owner_id=? ORDER BY updated_at DESC, created_at DESC
                """, userId);
        List<Map<String, Object>> sameSetRows = existingRows.stream()
                .filter(row -> targetKey.equals(canonicalQuestionKey(json.readStrings(String.valueOf(row.get("questionIds"))))))
                .toList();
        if (!sameSetRows.isEmpty()) {
            String primaryId = String.valueOf(sameSetRows.get(0).get("id"));
            Set<String> mergedModes = new LinkedHashSet<>();
            for (Map<String, Object> row : sameSetRows) {
                mergedModes.addAll(extractUsedModes(row));
            }
            mergedModes.add(initialMode.name());
            jdbc.update("""
                    UPDATE practice_papers
                    SET used_modes=CAST(? AS JSON), record_count=?, question_count=?, updated_at=NOW(3)
                    WHERE id=? AND owner_id=?
                    """, json.write(new ArrayList<>(mergedModes)), validIds.size(), flattened.size(), primaryId, userId);
            for (int i = 1; i < sameSetRows.size(); i++) {
                jdbc.update("DELETE FROM practice_papers WHERE id=? AND owner_id=?",
                        String.valueOf(sameSetRows.get(i).get("id")), userId);
            }
            Map<String, Object> merged = new LinkedHashMap<>(getRecord(userId, primaryId));
            merged.put("merged", true);
            return merged;
        }

        String paperId = UUID.randomUUID().toString();
        List<String> usedModes = List.of(initialMode.name());
        jdbc.update("""
                INSERT INTO practice_papers
                  (id, owner_id, title, question_ids, default_mode, used_modes, record_count, question_count, created_at, updated_at)
                VALUES (?, ?, ?, CAST(? AS JSON), ?, CAST(? AS JSON), ?, ?, NOW(3), NOW(3))
                """, paperId, userId, title, json.write(validIds), initialMode.name(), json.write(usedModes), validIds.size(), flattened.size());
        return getRecord(userId, paperId);
    }

    @Transactional
    public List<Map<String, Object>> listRecords(String userId, String keyword) {
        mergeDuplicateQuestionSets(userId);
        String cleanKeyword = keyword == null ? "" : keyword.trim();
        List<Map<String, Object>> rows;
        if (cleanKeyword.isEmpty()) {
            rows = jdbc.queryForList("""
                    SELECT id, title, question_ids AS questionIds, default_mode AS defaultMode,
                           used_modes AS usedModes, record_count AS recordCount,
                           question_count AS questionCount, created_at AS createdAt, updated_at AS updatedAt
                    FROM practice_papers WHERE owner_id=? ORDER BY updated_at DESC, created_at DESC LIMIT 100
                    """, userId);
        } else {
            rows = jdbc.queryForList("""
                    SELECT id, title, question_ids AS questionIds, default_mode AS defaultMode,
                           used_modes AS usedModes, record_count AS recordCount,
                           question_count AS questionCount, created_at AS createdAt, updated_at AS updatedAt
                    FROM practice_papers WHERE owner_id=? AND title LIKE ? ORDER BY updated_at DESC, created_at DESC LIMIT 100
                    """, userId, "%" + cleanKeyword + "%");
        }
        return rows.stream().map(this::formatRecordRow).toList();
    }

    public Map<String, Object> getRecord(String userId, String paperId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, title, question_ids AS questionIds, default_mode AS defaultMode,
                       used_modes AS usedModes, record_count AS recordCount,
                       question_count AS questionCount, created_at AS createdAt, updated_at AS updatedAt
                FROM practice_papers WHERE id=? AND owner_id=?
                """, paperId, userId);
        if (rows.isEmpty()) throw ApiException.notFound("组卷记录不存在。");
        return formatRecordRow(rows.get(0));
    }

    @Transactional
    public Map<String, Object> renameRecord(String userId, String paperId, RenamePaperCommand command) {
        String title = normalizeTitle(command == null ? null : command.title());
        int updated = jdbc.update("""
                UPDATE practice_papers SET title=?, updated_at=NOW(3) WHERE id=? AND owner_id=?
                """, title, paperId, userId);
        if (updated != 1) throw ApiException.notFound("组卷记录不存在。");
        return getRecord(userId, paperId);
    }

    @Transactional
    public void deleteRecord(String userId, String paperId) {
        int deleted = jdbc.update("DELETE FROM practice_papers WHERE id=? AND owner_id=?", paperId, userId);
        if (deleted != 1) throw ApiException.notFound("组卷记录不存在。");
    }

    @Transactional
    public GeneratedPaper exportSavedPaper(String userId, String paperId, String mode, String customTitle) {
        Map<String, Object> record = getRecord(userId, paperId);
        String savedTitle = String.valueOf(record.get("title"));
        String effectiveTitle = (customTitle != null && !customTitle.isBlank())
                ? normalizeTitle(customTitle) : savedTitle;
        ExportMode defaultMode = resolveModeValue(String.valueOf(record.get("defaultMode")), ExportMode.QUESTIONS_ONLY);
        ExportMode targetMode = resolveModeValue(mode, defaultMode);

        @SuppressWarnings("unchecked")
        List<String> usedModes = new ArrayList<>((List<String>) record.get("usedModes"));
        if (!usedModes.contains(targetMode.name())) {
            usedModes.add(targetMode.name());
            jdbc.update("""
                    UPDATE practice_papers SET used_modes=CAST(? AS JSON), updated_at=NOW(3)
                    WHERE id=? AND owner_id=?
                    """, json.write(usedModes), paperId, userId);
        }

        @SuppressWarnings("unchecked")
        List<String> questionIds = (List<String>) record.get("questionIds");
        return exportDocx(userId, new PaperCommand(effectiveTitle, questionIds, null, null, null, null, targetMode.name()));
    }

    public GeneratedPaper exportDocx(String userId, PaperCommand command) {
        String title = normalizeTitle(command == null ? null : command.title());
        ExportMode exportMode = resolveExportMode(command);

        List<QuestionRecord> selected = resolveQuestions(userId, command);
        if (selected.isEmpty()) {
            throw ApiException.badRequest("没有找到可用于组卷的历史错题（可能已被删除）。");
        }

        List<PaperItem> flattenedItems = flattenQuestions(selected);
        if (flattenedItems.isEmpty()) {
            throw ApiException.badRequest("没有找到可用于组卷的历史错题。");
        }

        String documentXml = buildDocumentXml(title, flattenedItems, exportMode);
        byte[] bytes = packageDocx(documentXml);
        String safeFileName = sanitizeFileName(title) + "-" + exportMode.fileSuffix + ".docx";
        return new GeneratedPaper(safeFileName, bytes);
    }

    private Map<String, Object> formatRecordRow(Map<String, Object> row) {
        String defaultMode = resolveModeValue(
                row.get("defaultMode") == null ? null : String.valueOf(row.get("defaultMode")),
                ExportMode.QUESTIONS_ONLY).name();
        List<String> rawUsed = row.get("usedModes") == null
                ? List.of() : json.readStrings(String.valueOf(row.get("usedModes")));
        List<String> usedModes = rawUsed.isEmpty() ? List.of(defaultMode) : rawUsed;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.get("id"));
        item.put("title", row.get("title"));
        item.put("questionIds", json.readStrings(String.valueOf(row.get("questionIds"))));
        item.put("defaultMode", defaultMode);
        item.put("usedModes", usedModes);
        item.put("recordCount", ((Number) row.get("recordCount")).intValue());
        item.put("questionCount", ((Number) row.get("questionCount")).intValue());
        item.put("createdAt", formatTimestamp(row.get("createdAt")));
        item.put("updatedAt", formatTimestamp(row.get("updatedAt")));
        return item;
    }

    private String formatTimestamp(Object value) {
        if (value == null) return "";
        String text = value.toString().replace('T', ' ');
        return text.length() >= 16 ? text.substring(0, 16) : text;
    }

    private String normalizeTitle(String rawTitle) {
        String title = rawTitle == null || rawTitle.isBlank() ? "历史错题专项练习卷" : rawTitle.trim();
        return title.length() > 80 ? title.substring(0, 80) : title;
    }

    private String sanitizeFileName(String title) {
        String safe = title.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        return safe.isEmpty() ? "历史错题专项练习卷" : safe;
    }

    private void mergeDuplicateQuestionSets(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, question_ids AS questionIds, default_mode AS defaultMode, used_modes AS usedModes
                FROM practice_papers WHERE owner_id=? ORDER BY updated_at DESC, created_at DESC
                """, userId);
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = canonicalQuestionKey(json.readStrings(String.valueOf(row.get("questionIds"))));
            if (key.isEmpty()) continue;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        for (List<Map<String, Object>> group : groups.values()) {
            if (group.size() <= 1) continue;
            String primaryId = String.valueOf(group.get(0).get("id"));
            Set<String> mergedModes = new LinkedHashSet<>();
            for (Map<String, Object> row : group) {
                mergedModes.addAll(extractUsedModes(row));
            }
            jdbc.update("UPDATE practice_papers SET used_modes=CAST(? AS JSON) WHERE id=? AND owner_id=?",
                    json.write(new ArrayList<>(mergedModes)), primaryId, userId);
            for (int i = 1; i < group.size(); i++) {
                jdbc.update("DELETE FROM practice_papers WHERE id=? AND owner_id=?",
                        String.valueOf(group.get(i).get("id")), userId);
            }
        }
    }

    private String canonicalQuestionKey(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        return ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
    }

    private List<String> extractUsedModes(Map<String, Object> row) {
        Set<String> modes = new LinkedHashSet<>();
        if (row.get("usedModes") != null) {
            for (String m : json.readStrings(String.valueOf(row.get("usedModes")))) {
                ExportMode parsed = tryParseMode(m);
                if (parsed != null) modes.add(parsed.name());
            }
        }
        if (modes.isEmpty() && row.get("defaultMode") != null) {
            ExportMode parsed = tryParseMode(String.valueOf(row.get("defaultMode")));
            if (parsed != null) modes.add(parsed.name());
        }
        return new ArrayList<>(modes);
    }

    private ExportMode parseRequiredMode(String rawMode) {
        ExportMode parsed = tryParseMode(rawMode);
        if (parsed == null) {
            throw ApiException.badRequest("请选择明确的试卷版本（学生做题版、老师做题版或纯答案版）。");
        }
        return parsed;
    }

    private ExportMode tryParseMode(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) return null;
        return switch (rawMode.trim().toUpperCase()) {
            case "QUESTIONS_ONLY", "NO_ANSWERS", "STUDENT" -> ExportMode.QUESTIONS_ONLY;
            case "ANSWERS_ONLY", "ANSWER_ONLY", "ANSWERS" -> ExportMode.ANSWERS_ONLY;
            case "WITH_ANSWERS", "TEACHER" -> ExportMode.WITH_ANSWERS;
            default -> null;
        };
    }

    private ExportMode resolveModeValue(String rawMode, ExportMode fallback) {
        ExportMode parsed = tryParseMode(rawMode);
        if (parsed != null) return parsed;
        return fallback == null ? ExportMode.QUESTIONS_ONLY : fallback;
    }

    private ExportMode resolveExportMode(PaperCommand command) {
        if (command != null && command.mode() != null && !command.mode().isBlank()) {
            return resolveModeValue(command.mode(), ExportMode.WITH_ANSWERS);
        }
        if (command != null && Boolean.FALSE.equals(command.includeAnswers())) {
            return ExportMode.QUESTIONS_ONLY;
        }
        return ExportMode.WITH_ANSWERS;
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
                        lines, hasOptions, answer, explanation, correction,
                        record.errorReason(), record.questionType(), record.chapterName()
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
                if (currentLines != null) blocks.add(new RawBlock(currentNum, currentLines));
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
        if (currentLines != null) blocks.add(new RawBlock(currentNum, currentLines));
        if (blocks.isEmpty()) return List.of(new RawBlock(null, lead));
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

    private String buildDocumentXml(String title, List<PaperItem> items, ExportMode mode) {
        StringBuilder body = new StringBuilder();

        if (mode == ExportMode.ANSWERS_ONLY) {
            body.append(paragraph(title + "（参考答案与解析）", 34, true, "center", 100));
            body.append(paragraph("日期：" + LocalDate.now() + "    共 " + items.size() + " 题    纯答案版", 20, false, "center", 200));
            appendAnswerKey(body, items, true);
        } else {
            String headerSuffix = mode == ExportMode.QUESTIONS_ONLY ? "" : "（老师做题版）";
            body.append(paragraph(title + headerSuffix, 36, true, "center", 120));
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

            if (mode == ExportMode.WITH_ANSWERS) {
                body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
                body.append(paragraph("参考答案与解析", 32, true, "center", 200));
                appendAnswerKey(body, items, false);
            }
        }

        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                """ + body + """
                    <w:sectPr>
                      <w:pgSz w:w="11906" w:h="16838"/>
                      <w:pgMar w:top="1200" w:right="1200" w:bottom="1200" w:left="1200"/>
                    </w:sectPr>
                  </w:body>
                </w:document>
                """;
    }

    private void appendAnswerKey(StringBuilder body, List<PaperItem> items, boolean compact) {
        // 省纸模式下，若均为简短选项答案且无解析，可先输出速查表再列有解析的条目
        if (compact) {
            StringBuilder quickRow = new StringBuilder();
            boolean hasAnyDetail = false;
            for (int i = 0; i < items.size(); i++) {
                PaperItem item = items.get(i);
                String ans = item.answer() != null && !item.answer().isBlank() ? item.answer().trim() : "待补";
                if (ans.length() <= 6) {
                    if (quickRow.length() > 0) quickRow.append("    ");
                    quickRow.append(i + 1).append(". ").append(ans);
                }
                if ((item.explanation() != null && !item.explanation().isBlank())
                        || (item.correction() != null && !item.correction().isBlank())
                        || ans.length() > 6) {
                    hasAnyDetail = true;
                }
            }
            if (quickRow.length() > 0) {
                body.append(paragraph("【答案速查】", 22, true, "left", 60));
                body.append(paragraph(quickRow.toString(), 21, false, "left", 160));
            }
            if (!hasAnyDetail) return;
            body.append(paragraph("【详细答案与解析】", 22, true, "left", 80));
        }

        for (int i = 0; i < items.size(); i++) {
            PaperItem item = items.get(i);
            int globalNum = i + 1;
            String answer = item.answer() != null && !item.answer().isBlank()
                    ? item.answer() : "（暂未录入参考答案）";
            if (compact && answer.length() <= 6
                    && (item.explanation() == null || item.explanation().isBlank())
                    && (item.correction() == null || item.correction().isBlank())) {
                continue;
            }
            body.append(paragraph(globalNum + ". 【参考答案】" + answer, 21, true, "left", 40));
            if (item.correction() != null && !item.correction().isBlank()) {
                body.append(paragraph("    【订正思路】" + item.correction(), 20, false, "left", 40));
            }
            if (item.explanation() != null && !item.explanation().isBlank()) {
                body.append(paragraph("    【解析】" + item.explanation(), 20, false, "left", compact ? 60 : 100));
            } else {
                body.append(paragraph("", 16, false, "left", 40));
            }
        }
    }

    private String paragraph(String text, int halfPointSize, boolean bold, String align, int spaceAfter) {
        String escaped = escapeXml(text == null ? "" : text);
        String boldTag = bold ? "<w:b/>" : "";
        return "<w:p>"
                + "<w:pPr><w:jc w:val=\"" + align + "\"/><w:spacing w:after=\"" + spaceAfter + "\" w:line=\"320\" w:lineRule=\"auto\"/></w:pPr>"
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

    private enum ExportMode {
        QUESTIONS_ONLY("学生做题版"),
        WITH_ANSWERS("老师做题版"),
        ANSWERS_ONLY("纯答案版");

        final String fileSuffix;

        ExportMode(String fileSuffix) {
            this.fileSuffix = fileSuffix;
        }
    }

    private record RawBlock(String originalNumber, List<String> lines) {}

    private record PaperItem(List<String> lines, boolean hasOptions, String answer, String explanation,
                             String correction, String errorReason, String questionType, String chapterName) {}

    public record SavePaperCommand(String title, List<String> questionIds, String mode) {}

    public record RenamePaperCommand(String title) {}

    public record ExportPaperCommand(String mode, String customTitle) {}

    public record PaperCommand(String title, List<String> questionIds, String chapterId,
                               String errorReason, Integer limit, Boolean includeAnswers, String mode) {}

    public record GeneratedPaper(String fileName, byte[] bytes) {}
}
