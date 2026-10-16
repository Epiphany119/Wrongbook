package com.cuotiben.storage.application;

import com.cuotiben.common.ApiException;
import com.cuotiben.question.infrastructure.QuestionRepository;
import com.cuotiben.storage.domain.AttachmentFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AttachmentStorageService {
    private static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp"
    );

    private final JdbcTemplate jdbc;
    private final QuestionRepository questions;
    private final Path root;
    private final long maxBytes;

    public AttachmentStorageService(JdbcTemplate jdbc, QuestionRepository questions,
                                    @Value("${app.storage.location:./data/uploads}") String location,
                                    @Value("${app.storage.max-file-bytes:10485760}") long maxBytes) {
        this.jdbc = jdbc;
        this.questions = questions;
        this.root = Path.of(location).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    @Transactional
    public Map<String, Object> upload(String userId, String questionId, String requestId, MultipartFile file) {
        if (!questions.owns(questionId, userId)) throw ApiException.notFound("错题不存在。");
        String clientRequestId = requestId == null || requestId.isBlank() ? null : requestId.trim();
        if (clientRequestId != null) {
            if (clientRequestId.length() > 80) throw ApiException.badRequest("图片请求标识过长。");
            List<Map<String, Object>> existing = jdbc.queryForList("""
                    SELECT id, question_id AS questionId, file_name AS fileName, mime_type AS mimeType, byte_size AS byteSize
                    FROM question_attachments WHERE owner_id=? AND client_request_id=?
                    """, userId, clientRequestId);
            if (!existing.isEmpty()) {
                Map<String, Object> row = existing.get(0);
                if (!questionId.equals(String.valueOf(row.get("questionId")))) {
                    throw ApiException.conflict("图片请求标识已用于其他错题。");
                }
                return Map.of("id", row.get("id"), "fileName", row.get("fileName"), "mimeType", row.get("mimeType"),
                        "byteSize", row.get("byteSize"), "fileUrl", "/api/v1/attachments/" + row.get("id"));
            }
        }
        if (file == null || file.isEmpty()) throw ApiException.badRequest("请选择要上传的图片。");
        if (file.getSize() > maxBytes) throw ApiException.badRequest("图片不能超过10MB。");
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String extension = ALLOWED.get(mime);
        if (extension == null) throw ApiException.badRequest("仅支持JPG、PNG或WEBP图片。");
        String id = UUID.randomUUID().toString();
        String storageKey = id + extension;
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) throw ApiException.badRequest("附件路径不合法。");
        String fileName = file.getOriginalFilename() == null ? "题目图片" : file.getOriginalFilename().replaceAll("[\\\\/\\r\\n]", "_");
        if (fileName.length() > 180) fileName = fileName.substring(fileName.length() - 180);
        try {
            Files.createDirectories(root);
            file.transferTo(target);
            try {
                jdbc.update("""
                        INSERT INTO question_attachments
                            (id, owner_id, question_id, client_request_id, file_name, mime_type, byte_size, storage_key, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(3))
                        """, id, userId, questionId, clientRequestId, fileName, mime, file.getSize(), storageKey);
            } catch (RuntimeException exception) {
                Files.deleteIfExists(target);
                throw exception;
            }
            return Map.of("id", id, "fileName", fileName, "mimeType", mime, "byteSize", file.getSize(),
                    "fileUrl", "/api/v1/attachments/" + id);
        } catch (IOException exception) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "UPLOAD_FAILED", "图片保存失败，请重试。");
        }
    }

    public AttachmentFile load(String userId, String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT file_name AS fileName, mime_type AS mimeType, byte_size AS byteSize, storage_key AS storageKey
                FROM question_attachments WHERE id=? AND owner_id=?
                """, id, userId);
        if (rows.isEmpty()) throw ApiException.notFound("附件不存在。");
        Map<String, Object> row = rows.get(0);
        Path path = root.resolve(String.valueOf(row.get("storageKey"))).normalize();
        if (!path.startsWith(root) || !Files.exists(path)) throw ApiException.notFound("附件文件不存在。");
        return new AttachmentFile(path, String.valueOf(row.get("fileName")),
                String.valueOf(row.get("mimeType")), ((Number) row.get("byteSize")).longValue());
    }

    public void deleteAllForOwner(String ownerId) {
        List<String> keys = jdbc.query("SELECT storage_key FROM question_attachments WHERE owner_id=?",
                (rs, row) -> rs.getString(1), ownerId);
        for (String key : keys) {
            Path path = root.resolve(key).normalize();
            if (!path.startsWith(root)) throw ApiException.forbidden("附件存储路径校验失败。");
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "FILE_DELETE_FAILED", "附件清理失败，账号数据未删除。");
            }
        }
    }
}
