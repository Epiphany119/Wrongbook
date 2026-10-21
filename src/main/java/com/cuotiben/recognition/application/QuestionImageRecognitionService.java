package com.cuotiben.recognition.application;

import com.cuotiben.common.ApiException;
import com.cuotiben.recognition.config.GlmOcrProperties;
import com.cuotiben.recognition.interfaces.QuestionImageRecognitionResponse;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Map;

@Service
public class QuestionImageRecognitionService {
    private static final String UNREADABLE_MESSAGE = "未识别到清晰文字，请重新拍摄或选择图片。";
    private static final Logger log = LoggerFactory.getLogger(QuestionImageRecognitionService.class);
    private final GlmOcrProperties properties;
    private final RestClient restClient;

    public QuestionImageRecognitionService(GlmOcrProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        requestFactory.setReadTimeout(properties.getTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public QuestionImageRecognitionResponse recognize(MultipartFile file) {
        if (!properties.isEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OCR_DISABLED", "图片文字识别暂未启用。");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OCR_NOT_CONFIGURED",
                    "图片文字识别尚未配置，请在后端 application-local.yml 中填写 GLM API Key。");
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

        String dataUri = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        JsonNode response = callGlmOcr(dataUri);
        String text = normalizeText(extractRecognizedText(response));
        if (!isReadable(text)) {
            return new QuestionImageRecognitionResponse(false, "", UNREADABLE_MESSAGE);
        }
        return new QuestionImageRecognitionResponse(true, text, "识别完成，请核对题目文字。");
    }

    private JsonNode callGlmOcr(String dataUri) {
        try {
            JsonNode body = restClient.post()
                    .uri(properties.getApiUrl())
                    // GLM-OCR layout_parsing uses the raw API key in Authorization (without "Bearer ").
                    .header(HttpHeaders.AUTHORIZATION, properties.getApiKey().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", properties.getModel(), "file", dataUri))
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || body.isNull()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OCR_EMPTY_RESPONSE",
                        "图片识别服务没有返回结果，请稍后重试。");
            }
            return body;
        } catch (RestClientResponseException error) {
            String responseBody = error.getResponseBodyAsString();
            String detail = responseBody == null ? "" : responseBody.replaceAll("[\\r\\n\\t]+", " ").trim();
            if (detail.length() > 500) detail = detail.substring(0, 500);
            log.warn("GLM-OCR returned HTTP {}: {}", error.getStatusCode().value(), detail);
            if (error.getStatusCode().value() == 401 || error.getStatusCode().value() == 403) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OCR_AUTH_FAILED",
                        "GLM API Key 无效或没有调用权限，请检查后端 application-local.yml。");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OCR_PROVIDER_ERROR",
                    "图片识别服务返回异常（HTTP " + error.getStatusCode().value() + "），请查看 IDEA 后端控制台。");
        } catch (RestClientException error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OCR_PROVIDER_UNAVAILABLE",
                    "连接图片识别服务失败，请检查电脑网络后重试。");
        }
    }

    private String extractRecognizedText(JsonNode response) {
        for (String field : new String[]{"md_results", "text", "content", "data"}) {
            String text = extractTextNode(response.get(field));
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private String extractTextNode(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : node) {
                String value = extractTextNode(item);
                if (StringUtils.hasText(value)) {
                    if (text.length() > 0) text.append('\n');
                    text.append(value);
                }
            }
            return text.toString();
        }
        if (node.isObject()) {
            for (String field : new String[]{"md_results", "text", "content", "result"}) {
                String text = extractTextNode(node.get(field));
                if (StringUtils.hasText(text)) return text;
            }
        }
        return "";
    }

    private String normalizeText(String source) {
        if (!StringUtils.hasText(source)) return "";
        String text = source.replaceAll("(?is)!\\[[^]]*]\\(page=\\d+,bbox=\\[[^]]*]\\)", "")
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
                || normalized.equals("notextfound");
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
}
