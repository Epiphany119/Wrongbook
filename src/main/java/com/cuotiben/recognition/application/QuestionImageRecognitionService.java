package com.cuotiben.recognition.application;

import com.cuotiben.common.ApiException;
import com.cuotiben.recognition.config.DeepSeekVisionProperties;
import com.cuotiben.recognition.interfaces.QuestionImageRecognitionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QuestionImageRecognitionService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long BUSY_RETRY_DELAY_MILLIS = 1_200L;
    private static final String UNREADABLE_MESSAGE = "未识别到清晰题目，请重新拍摄或选择图片。";
    private static final String NO_QUESTION_TEXT = "NO_QUESTION_TEXT";
    private static final Pattern SECTION_LABEL = Pattern.compile(
            "(?:#{1,6}\\s*)?\\*{0,2}(?:[【\\[]\\s*)?(答案解析|解题思路|正确答案|参考答案|标准答案|答案|解析)"
                    + "(?:\\s*[】\\]])?\\*{0,2}\\s*(?:[:：=]\\s*\\*{0,2}\\s*|$)");
    private static final Pattern QUESTION_LINE_MARKER = Pattern.compile(
            "^(?:#{1,6}\\s*|[-+*]\\s+|>\\s*)?(?:\\*\\*)?(?:第\\s*)?(\\d{1,3})\\s*(?:题)?([.、．:：)）])(?:\\*\\*)?\\s*(.*)$");
    private static final Pattern EMBEDDED_ANSWER_HEAD = Pattern.compile(
            "^(?:✅\\s*)?(?:答案解析|解题思路|正确答案|参考答案|标准答案|答案|解析)(?:\\s*[:：=]|(?:是|为)(?!什么|如何|怎样|为什么|是否|哪|几|谁)|\\s+)");
    private static final Pattern INLINE_NUMBERED_ANSWER = Pattern.compile(
            "(?:^|[\\s。；;！!？?,，])(?:第\\s*)?(\\d{1,3})\\s*(?:题)?\\s*[.、．:：)）-]\\s*");
    private static final String EXTRACTION_PROMPT = """
            请从图片中识别历史学科题目或参考答案页，并把内容结构化提取。只输出一个合法 JSON 对象，不要 Markdown 代码围栏或额外说明，格式如下：
            {"contentKind":"QUESTIONS、MIXED、ANSWER_SHEET 或 NONE","questionText":"题面 Markdown（保留原题号，纯答案页时为空字符串）","correctAnswer":"清晰印刷的标准答案（保留原题号，如 30. B\\n31. A；没有时为空字符串）","explanation":"图片中印刷的解析（保留原题号；没有时为空字符串）","answerSource":"PRINTED、NONE 或 HANDWRITTEN_ONLY","questionType":"选择题、填空题、材料分析题、简答题、论述题 或 其他","suggestedTags":["1到3个简洁的历史朝代或核心考点标签"],"answerMap":{"原题号":"对应印刷答案，例如 30: B"}}
            判定 contentKind 的规则：
            1. 如果图片主要是参考答案列表/答案汇总页（例如出现“答案”“参考答案”标题以及大量“1.B 2.B 3.A ... 50.B”的编号答案，即使顶部残留一两行题干），请设 contentKind 为 "ANSWER_SHEET"，把图中所有印刷的“原题号->答案”完整提取到 answerMap（键为纯数字字符串如 "30"，值为答案如 "B"），同时按“原题号. 答案”分行填入 correctAnswer，questionText 设为空字符串！
            2. 如果图片同时包含完整题目和对应答案，设 contentKind 为 "MIXED"，questionText 保留原题号，answerMap 和 correctAnswer 同样保留原题号。
            3. 如果图片只有题目没有清晰印刷答案，设 contentKind 为 "QUESTIONS"，questionText 必须保留图片原本的题号（例如 30. 31. ... 40.），不要自行改号（后端会统一重排并用于跨页匹配答案）。
            4. questionText 只放章节标题、原题号、材料、题干和 A-D 选项；不要把答案或解析混在题面中。
            5. correctAnswer 与 answerMap 只抄录清晰印刷的标准答案；红笔/手写订正、勾叉、批注不作为标准答案（只有手写订正时 answerSource 为 HANDWRITTEN_ONLY 且 correctAnswer 为空）。
            6. questionType 请根据题面形式判断；suggestedTags 提取 1 到 3 个简短历史时期或核心考点标签（纯答案页可留空数组）。
            7. 只能抄录图片里确实出现的印刷内容，不得解题、猜测答案或编造解析；无法辨认的印刷内容用【无法辨认】标记。
            8. 如果图片既不是练习题/试卷，也不是参考答案页，输出：{"contentKind":"NONE","questionText":"NO_QUESTION_TEXT","correctAnswer":"","explanation":"","answerSource":"NONE","questionType":"","suggestedTags":[],"answerMap":{}}
            """;
    private static final Logger log = LoggerFactory.getLogger(QuestionImageRecognitionService.class);
    private final DeepSeekVisionProperties properties;
    private final RestClient restClient;

    public QuestionImageRecognitionService(DeepSeekVisionProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        requestFactory.setReadTimeout(properties.getTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public QuestionImageRecognitionResponse recognize(MultipartFile file) {
        if (!properties.isEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VISION_DISABLED", "图片识题暂未启用。");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VISION_NOT_CONFIGURED",
                    "图片识题尚未配置，请在后端 application-local.yml 中填写 DeepSeek API Key。");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择一张题目图片。");
        }
        if (file.getSize() > properties.getMaxImageBytes()) {
            throw ApiException.badRequest("图片超过大小限制，请压缩后重新选择。");
        }

        byte[] imageBytes;
        try {
            imageBytes = file.getBytes();
        } catch (IOException error) {
            throw ApiException.badRequest("读取图片失败，请重新选择图片。");
        }
        String mimeType = detectMimeType(imageBytes);
        if (mimeType == null) {
            throw ApiException.badRequest("当前只支持 JPG 或 PNG 图片，请重新选择。");
        }

        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        JsonNode response = callDeepSeekVision(dataUrl);
        String modelText = extractModelText(response);
        RecognizedContent content = parseModelContent(modelText);

        String rawQuestionText = normalizeText(content.questionText());
        String rawCorrectAnswer = normalizeAnswerField(content.correctAnswer(), false);
        String rawExplanation = normalizeAnswerField(content.explanation(), true);
        Map<String, String> parsedAnswerMap = buildAnswerMap(content.answerMap(), rawCorrectAnswer);
        if (parsedAnswerMap.isEmpty()) {
            Map<String, String> fallbackFromRaw = parseGenericNumberedMap(modelText);
            if (fallbackFromRaw.size() >= 3) {
                parsedAnswerMap.putAll(fallbackFromRaw);
            }
        }
        Map<String, String> parsedExplanationMap = parseGenericNumberedMap(rawExplanation);

        boolean questionReadable = isReadable(rawQuestionText);
        RenumberedResult previewRenumber = questionReadable
                ? renumberFromOne(rawQuestionText, rawCorrectAnswer, rawExplanation)
                : new RenumberedResult("", "", "", List.of());
        int detectedQuestionCount = previewRenumber.originalNumbers().size();

        // 即使答案页顶部残留 1~2 道题干（如第 50 题），只要识别出的编号答案数量明显多于题干数，即判定为纯答案页
        boolean mostlyAnswerSheet = parsedAnswerMap.size() >= 3
                && parsedAnswerMap.size() > Math.max(1, detectedQuestionCount) * 2;
        boolean isAnswerSheet = "ANSWER_SHEET".equals(content.contentKind())
                || (!questionReadable && !parsedAnswerMap.isEmpty())
                || mostlyAnswerSheet;

        log.info("Vision parsed: contentKind={}, questionReadable={}, detectedQuestions={}, answerMapSize={}, isAnswerSheet={}",
                content.contentKind(), questionReadable, detectedQuestionCount, parsedAnswerMap.size(), isAnswerSheet);

        if (isAnswerSheet && !parsedAnswerMap.isEmpty()) {
            String formattedAnswers = formatNumberedMap(parsedAnswerMap);
            String msg = "已识别答案页（共 " + parsedAnswerMap.size() + " 条参考答案），将自动按原题号匹配。";
            return new QuestionImageRecognitionResponse(
                    true, "ANSWER_SHEET", "", "", formattedAnswers, rawExplanation,
                    "PRINTED", "", List.of(), List.of(), parsedAnswerMap, parsedExplanationMap, msg);
        }

        if (!questionReadable) {
            return new QuestionImageRecognitionResponse(
                    false, "NONE", "", "", "", "", "NONE", "", List.of(), List.of(), Map.of(), Map.of(), UNREADABLE_MESSAGE);
        }

        String questionText = previewRenumber.questionText();
        String correctAnswer = previewRenumber.correctAnswer();
        String explanation = previewRenumber.explanation();
        List<String> originalNumbers = previewRenumber.originalNumbers();

        if (!originalNumbers.isEmpty() && parsedAnswerMap.isEmpty() && StringUtils.hasText(rawCorrectAnswer)) {
            parsedAnswerMap = mapByExpectedNumbers(rawCorrectAnswer, originalNumbers);
        }
        if (!originalNumbers.isEmpty() && parsedExplanationMap.isEmpty() && StringUtils.hasText(rawExplanation)) {
            parsedExplanationMap = mapByExpectedNumbers(rawExplanation, originalNumbers);
        }

        String answerSource = StringUtils.hasText(correctAnswer)
                ? "PRINTED" : normalizeAnswerSource(content.answerSource());
        String contentKind = StringUtils.hasText(correctAnswer) ? "MIXED" : "QUESTIONS";
        String questionType = normalizeQuestionType(content.questionType());
        List<String> suggestedTags = content.suggestedTags() == null ? List.of() : content.suggestedTags();
        String message = StringUtils.hasText(correctAnswer)
                ? "识别完成，已提取题目、参考答案与分类建议，请核对。"
                : "识别完成，未发现清晰印刷答案；你可以继续拍答案页自动匹配，或手动补充。";
        return new QuestionImageRecognitionResponse(
                true, contentKind, questionText, questionText, correctAnswer, explanation,
                answerSource, questionType, suggestedTags, originalNumbers, parsedAnswerMap, parsedExplanationMap, message);
    }

    private RenumberedResult renumberFromOne(String questionText, String correctAnswer, String explanation) {
        String[] lines = questionText.split("\\n");
        List<String> originalNumbers = new java.util.ArrayList<>();
        StringBuilder rewrittenQuestion = new StringBuilder();
        int nextNumber = 1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher matcher = QUESTION_LINE_MARKER.matcher(line.trim());
            if (matcher.matches() && !EMBEDDED_ANSWER_HEAD.matcher(matcher.group(3)).find()) {
                originalNumbers.add(matcher.group(1));
                if (i > 0) rewrittenQuestion.append('\n');
                rewrittenQuestion.append(nextNumber++).append(". ").append(matcher.group(3).trim());
            } else {
                if (i > 0) rewrittenQuestion.append('\n');
                rewrittenQuestion.append(line);
            }
        }
        if (originalNumbers.isEmpty()) {
            return new RenumberedResult(questionText, correctAnswer, explanation, List.of());
        }
        String rewrittenAnswer = renumberNumberedField(correctAnswer, originalNumbers);
        String rewrittenExplanation = renumberNumberedField(explanation, originalNumbers);
        return new RenumberedResult(rewrittenQuestion.toString(), rewrittenAnswer, rewrittenExplanation, originalNumbers);
    }

    private String renumberNumberedField(String source, List<String> originalNumbers) {
        if (!StringUtils.hasText(source) || originalNumbers.isEmpty()) return source;
        List<String> segments = extractByExpectedNumbers(source, originalNumbers);
        if (segments == null) return source;
        if (originalNumbers.size() == 1) {
            return segments.get(0);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            String item = segments.get(i);
            if (!StringUtils.hasText(item)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(i + 1).append(". ").append(item.trim());
        }
        return out.length() == 0 ? source : out.toString();
    }

    private Map<String, String> mapByExpectedNumbers(String source, List<String> expectedNumbers) {
        List<String> segments = extractByExpectedNumbers(source, expectedNumbers);
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (segments == null) return map;
        for (int i = 0; i < expectedNumbers.size() && i < segments.size(); i++) {
            if (StringUtils.hasText(segments.get(i))) {
                map.put(expectedNumbers.get(i), segments.get(i).trim());
            }
        }
        return map;
    }

    private Map<String, String> buildAnswerMap(Map<String, String> modelMap, String rawCorrectAnswer) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (StringUtils.hasText(rawCorrectAnswer)) {
            result.putAll(parseGenericNumberedMap(rawCorrectAnswer));
        }
        if (modelMap != null) {
            modelMap.forEach((k, v) -> {
                String cleanKey = k == null ? "" : k.replaceAll("[^0-9]", "");
                String cleanVal = v == null ? "" : v.trim();
                if (!cleanKey.isEmpty() && !cleanVal.isEmpty()) {
                    result.put(cleanKey, cleanVal);
                }
            });
        }
        return result;
    }

    private Map<String, String> parseGenericNumberedMap(String source) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (!StringUtils.hasText(source)) return map;
        String text = source.replace('\r', '\n').trim();
        List<int[]> spans = new java.util.ArrayList<>();
        List<String> keys = new java.util.ArrayList<>();
        Matcher matcher = INLINE_NUMBERED_ANSWER.matcher(text);
        while (matcher.find()) {
            keys.add(matcher.group(1));
            spans.add(new int[]{matcher.start(), matcher.end()});
        }
        for (int i = 0; i < keys.size(); i++) {
            int bodyStart = spans.get(i)[1];
            int bodyEnd = i + 1 < keys.size() ? spans.get(i + 1)[0] : text.length();
            String val = text.substring(bodyStart, bodyEnd).trim();
            if (!val.isEmpty()) {
                map.put(keys.get(i), val);
            }
        }
        return map;
    }

    private String formatNumberedMap(Map<String, String> map) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(entry.getKey()).append(". ").append(entry.getValue());
        }
        return out.toString();
    }

    private List<String> extractByExpectedNumbers(String source, List<String> expectedNumbers) {
        String text = source.replace('\r', '\n').trim();
        if (text.isEmpty() || expectedNumbers.isEmpty()) return null;
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
        List<String> result = new java.util.ArrayList<>(expectedNumbers.size());
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

    private record RenumberedResult(String questionText, String correctAnswer, String explanation, List<String> originalNumbers) {}

    private RecognizedContent parseModelContent(String modelText) {
        if (!StringUtils.hasText(modelText) || isNoTextNotice(modelText)) {
            return RecognizedContent.empty();
        }

        String json = stripCodeFence(modelText);
        int objectStart = json.indexOf('{');
        int objectEnd = json.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) json = json.substring(objectStart, objectEnd + 1);
        try {
            JsonNode root = JSON.readTree(json);
            if (root != null && root.isObject()) {
                String contentKind = normalizeContentKind(firstText(root, "contentKind", "content_kind", "kind"));
                String questionText = firstText(root, "questionText", "question_text", "question", "text", "markdown");
                String answer = firstText(root, "correctAnswer", "correct_answer", "referenceAnswer",
                        "reference_answer", "standardAnswer", "standard_answer", "answerText", "answer_text", "answer");
                String explanation = firstText(root, "explanation", "analysis", "analysisText", "analysis_text");
                String answerSource = firstText(root, "answerSource", "answer_source");
                String questionType = firstText(root, "questionType", "question_type", "type");
                List<String> tags = extractTags(root.path("suggestedTags"), root.path("suggested_tags"), root.path("tags"));
                Map<String, String> answerMap = extractStringMap(root.path("answerMap"), root.path("answer_map"), root.path("answers"));
                if ("ANSWER_SHEET".equals(contentKind)) {
                    return new RecognizedContent("ANSWER_SHEET", "", answer, explanation, "PRINTED", "", List.of(), answerMap);
                }
                return splitEmbeddedSections(contentKind, questionText, answer, explanation, answerSource, questionType, tags, answerMap);
            }
        } catch (IOException ignored) {
            // Keep older/plain-text model responses working while the new prompt uses JSON fields.
        }
        if (json.contains("\"questionText\"") || json.startsWith("{")) {
            return RecognizedContent.empty();
        }
        return splitEmbeddedSections("QUESTIONS", modelText, "", "", "", "", List.of(), Map.of());
    }

    private Map<String, String> extractStringMap(JsonNode... candidates) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (JsonNode node : candidates) {
            if (node != null && node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    String key = entry.getKey() == null ? "" : entry.getKey().replaceAll("[^0-9]", "");
                    String val = entry.getValue().asText("").trim();
                    if (!key.isEmpty() && !val.isEmpty()) {
                        map.put(key, val);
                    }
                }
                if (!map.isEmpty()) return map;
            }
        }
        return map;
    }

    private String normalizeContentKind(String value) {
        if (!StringUtils.hasText(value)) return "QUESTIONS";
        return switch (value.trim().toUpperCase()) {
            case "ANSWER_SHEET", "ANSWERS", "ANSWER" -> "ANSWER_SHEET";
            case "MIXED" -> "MIXED";
            case "NONE" -> "NONE";
            default -> "QUESTIONS";
        };
    }

    private RecognizedContent splitEmbeddedSections(String contentKind, String questionText, String correctAnswer,
                                                     String explanation, String answerSource,
                                                     String questionType, List<String> suggestedTags,
                                                     Map<String, String> answerMap) {
        StringBuilder question = new StringBuilder();
        StringBuilder embeddedAnswer = new StringBuilder();
        StringBuilder embeddedExplanation = new StringBuilder();
        String section = "question";
        for (String line : String.valueOf(questionText == null ? "" : questionText).replace('\r', '\n').split("\\n")) {
            Matcher matcher = SECTION_LABEL.matcher(line);
            int cursor = 0;
            boolean foundLabel = false;
            while (matcher.find()) {
                foundLabel = true;
                appendSection(section, line.substring(cursor, matcher.start()), question, embeddedAnswer, embeddedExplanation);
                section = isExplanationLabel(matcher.group(1)) ? "explanation" : "answer";
                cursor = matcher.end();
            }
            if (foundLabel) {
                appendSection(section, line.substring(cursor), question, embeddedAnswer, embeddedExplanation);
            } else {
                appendSection(section, line, question, embeddedAnswer, embeddedExplanation);
            }
        }

        String declaredSource = normalizeAnswerSource(answerSource);
        boolean handwrittenOnly = "HANDWRITTEN_ONLY".equals(declaredSource);
        String answer = handwrittenOnly ? ""
                : (StringUtils.hasText(correctAnswer) ? correctAnswer : embeddedAnswer.toString());
        String parsedExplanation = firstNonBlank(explanation, embeddedExplanation.toString());
        String source = StringUtils.hasText(answer) ? "PRINTED" : declaredSource;
        return new RecognizedContent(contentKind, question.toString(), answer, parsedExplanation, source, questionType, suggestedTags, answerMap);
    }

    private List<String> extractTags(JsonNode... candidates) {
        java.util.List<String> tags = new java.util.ArrayList<>();
        for (JsonNode node : candidates) {
            if (node != null && node.isArray()) {
                for (JsonNode item : node) {
                    if (item.isTextual()) {
                        String clean = item.asText().trim();
                        if (!clean.isEmpty() && clean.length() <= 20 && !tags.contains(clean)) {
                            tags.add(clean);
                            if (tags.size() >= 3) return tags;
                        }
                    }
                }
                if (!tags.isEmpty()) return tags;
            }
        }
        return tags;
    }

    private String normalizeQuestionType(String value) {
        if (!StringUtils.hasText(value)) return "";
        String v = value.trim();
        if (v.contains("选择")) return "选择题";
        if (v.contains("填空")) return "填空题";
        if (v.contains("材料") || v.contains("阅读")) return "材料分析题";
        if (v.contains("简答") || v.contains("解答")) return "简答题";
        if (v.contains("论述")) return "论述题";
        return "其他";
    }

    private void appendSection(String section, String value, StringBuilder question,
                               StringBuilder answer, StringBuilder explanation) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return;
        StringBuilder target = switch (section) {
            case "answer" -> answer;
            case "explanation" -> explanation;
            default -> question;
        };
        if (target.length() > 0) target.append('\n');
        target.append(trimmed);
    }

    private boolean isExplanationLabel(String label) {
        return "答案解析".equals(label) || "解析".equals(label) || "解题思路".equals(label);
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (value.isTextual() && StringUtils.hasText(value.asText())) return value.asText();
        }
        return "";
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private String normalizeAnswerSource(String value) {
        String source = value == null ? "" : value.trim().toUpperCase();
        return switch (source) {
            case "PRINTED", "打印", "印刷" -> "PRINTED";
            case "HANDWRITTEN_ONLY", "HANDWRITTEN", "手写", "手写订正", "红笔订正" -> "HANDWRITTEN_ONLY";
            case "NONE", "无", "未发现" -> "NONE";
            default -> "NONE";
        };
    }

    private String stripCodeFence(String value) {
        return value == null ? "" : value.trim()
                .replaceAll("(?s)^```(?:json)?\\s*|\\s*```$", "").trim();
    }

    private String normalizeAnswerField(String value, boolean explanation) {
        String normalized = normalizeText(value);
        String label = explanation
                ? "(?:答案解析|解析|解题思路)"
                : "(?:正确答案|参考答案|标准答案|答案)";
        return normalized.replaceFirst("(?s)^\\*{0,2}" + label + "\\*{0,2}\\s*[:：=]\\s*", "").trim();
    }

    private JsonNode callDeepSeekVision(String imageDataUrl) {
        try {
            return sendVisionRequest(imageDataUrl);
        } catch (RestClientResponseException firstError) {
            if (!isRetryable(firstError)) throw mapProviderError(firstError);
            log.warn("DeepSeek Vision returned transient HTTP {}; retrying once", firstError.getStatusCode().value());
            waitBeforeRetry();
            try {
                return sendVisionRequest(imageDataUrl);
            } catch (RestClientResponseException retryError) {
                throw mapProviderError(retryError);
            } catch (RestClientException retryError) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "VISION_PROVIDER_UNAVAILABLE",
                        "连接图片识别服务失败，请检查电脑网络后重试。照片和题目草稿仍保存在本机。");
            }
        } catch (RestClientException error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "VISION_PROVIDER_UNAVAILABLE",
                    "连接图片识别服务失败，请检查电脑网络后重试。照片和题目草稿仍保存在本机。");
        }
    }

    private JsonNode sendVisionRequest(String imageDataUrl) {
        List<Map<String, Object>> content = List.of(
                Map.of("type", "text", "text", EXTRACTION_PROMPT),
                Map.of("type", "image_url", "image_url", Map.of("url", imageDataUrl))
        );
        Map<String, Object> request = Map.of(
                "model", properties.getModel(),
                "messages", List.of(Map.of("role", "user", "content", content)),
                "stream", false,
                "thinking", Map.of("type", "disabled"),
                "temperature", 0.1
        );
        JsonNode body = restClient.post()
                .uri(properties.getApiUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey().trim())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        if (body == null || body.isNull()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "VISION_EMPTY_RESPONSE",
                    "图片识别服务没有返回结果，请稍后重试。照片和题目草稿仍保存在本机。");
        }
        return body;
    }

    private boolean isRetryable(RestClientResponseException error) {
        int status = error.getStatusCode().value();
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private String providerErrorCode(RestClientResponseException error) {
        try {
            JsonNode code = JSON.readTree(error.getResponseBodyAsString()).path("error").path("code");
            return code.isMissingNode() || code.isNull() ? "" : code.asText();
        } catch (IOException ignored) {
            return "";
        }
    }

    private void waitBeforeRetry() {
        try {
            Thread.sleep(BUSY_RETRY_DELAY_MILLIS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VISION_RETRY_INTERRUPTED",
                    "图片识题请求被中断，请稍后重试。照片和题目草稿仍保存在本机。");
        }
    }

    private ApiException mapProviderError(RestClientResponseException error) {
        int status = error.getStatusCode().value();
        String code = providerErrorCode(error);
        String message = providerErrorMessage(error);
        log.warn("DeepSeek Vision returned HTTP {} (provider code {}): {}",
                status, code.isEmpty() ? "unknown" : code, message);
        if (status == 401 || status == 403) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VISION_AUTH_FAILED",
                    "DeepSeek API Key 无效或没有调用权限，请检查后端 application-local.yml。");
        }
        if (status == 402) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VISION_QUOTA_EXHAUSTED",
                    "DeepSeek 账户余额不足，请检查平台余额。照片和题目草稿仍保存在本机。");
        }
        if (status == 429) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VISION_RATE_LIMITED",
                    "DeepSeek 图片识题请求达到速率或并发限制，请稍后重试。照片和题目草稿仍保存在本机。");
        }
        if (status == 500 || status == 502 || status == 503 || status == 504) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VISION_PROVIDER_BUSY",
                    "DeepSeek 图片识题服务暂时繁忙，请稍后重试。照片和题目草稿仍保存在本机。");
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "VISION_PROVIDER_ERROR",
                "DeepSeek 图片识别请求失败（HTTP " + status + "），请检查 IDEA 后端日志。照片和题目草稿仍保存在本机。");
    }

    private String providerErrorMessage(RestClientResponseException error) {
        try {
            String message = JSON.readTree(error.getResponseBodyAsString()).path("error").path("message").asText("");
            message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
            return message.length() > 300 ? message.substring(0, 300) : message;
        } catch (IOException ignored) {
            return "response body unavailable";
        }
    }

    private String extractModelText(JsonNode response) {
        JsonNode error = response.path("error");
        if (error.isObject()) {
            String message = error.path("message").asText("上游模型返回错误");
            log.warn("DeepSeek Vision returned an error payload: {}", message);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "VISION_PROVIDER_ERROR",
                    "DeepSeek 图片识题服务返回异常，请查看 IDEA 后端控制台。");
        }
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        return content.isTextual() ? content.asText() : "";
    }

    private String normalizeText(String source) {
        if (!StringUtils.hasText(source)) return "";
        String text = source.replaceAll("(?s)^\\s*```(?:markdown|md)?\\s*|\\s*```\\s*$", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</tr\\s*>", "\n")
                .replaceAll("(?i)</(td|th)\\s*>", " | ")
                .replaceAll("(?s)<[^>]+>", " ");
        text = HtmlUtils.htmlUnescape(text).replace('\r', '\n');
        String[] lines = text.split("\\n");
        StringBuilder formatted = new StringBuilder();
        boolean previousBlank = false;
        for (String line : lines) {
            String cleanLine = line.replaceAll("[\\t\\x0B\\f ]+", " ").trim();
            if (cleanLine.isEmpty()) {
                if (!previousBlank && formatted.length() > 0) formatted.append('\n');
                previousBlank = true;
                continue;
            }
            if (formatted.length() > 0 && formatted.charAt(formatted.length() - 1) != '\n') formatted.append('\n');
            formatted.append(cleanLine);
            previousBlank = false;
        }
        return formatted.toString().trim();
    }

    private boolean isReadable(String text) {
        if (!StringUtils.hasText(text)) return false;
        if (isNoTextNotice(text)) return false;
        long nonWhitespace = text.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();
        long contentCharacters = text.codePoints().filter(Character::isLetterOrDigit).count();
        if (contentCharacters < properties.getMinReadableCharacters() || nonWhitespace == 0) return false;
        if (contentCharacters / (double) nonWhitespace < properties.getMinContentRatio()) return false;
        return !hasLongRepeatedCharacter(text, 6);
    }

    private boolean isNoTextNotice(String text) {
        String normalized = text.toLowerCase().replaceAll("[\\s\\p{Punct}\\p{P}]+", "");
        return normalized.equals("未检测到文字")
                || normalized.equals("未检测到任何文字")
                || normalized.equals("未识别到文字")
                || normalized.equals("未识别到任何文字")
                || normalized.equals("图片中没有文字")
                || normalized.equals("图片中未检测到文字")
                || normalized.equals("notextdetected")
                || normalized.equals("notextfound")
                || normalized.equals("noquestiontext");
    }

    private boolean hasLongRepeatedCharacter(String text, int limit) {
        int previous = -1;
        int runLength = 0;
        for (int codePoint : text.codePoints().toArray()) {
            if (Character.isWhitespace(codePoint)) {
                previous = -1;
                runLength = 0;
            } else if (codePoint == previous) {
                runLength++;
                if (runLength >= limit) return true;
            } else {
                previous = codePoint;
                runLength = 1;
            }
        }
        return false;
    }

    private String detectMimeType(byte[] imageBytes) {
        if (imageBytes.length >= 8
                && imageBytes[0] == (byte) 0x89 && imageBytes[1] == 0x50 && imageBytes[2] == 0x4e
                && imageBytes[3] == 0x47 && imageBytes[4] == 0x0d && imageBytes[5] == 0x0a
                && imageBytes[6] == 0x1a && imageBytes[7] == 0x0a) {
            return "image/png";
        }
        if (imageBytes.length >= 3 && imageBytes[0] == (byte) 0xff && imageBytes[1] == (byte) 0xd8
                && imageBytes[2] == (byte) 0xff) {
            return "image/jpeg";
        }
        return null;
    }

    private record RecognizedContent(String contentKind, String questionText, String correctAnswer,
                                     String explanation, String answerSource,
                                     String questionType, List<String> suggestedTags,
                                     Map<String, String> answerMap) {
        private static RecognizedContent empty() {
            return new RecognizedContent("NONE", "", "", "", "NONE", "", List.of(), Map.of());
        }
    }
}
