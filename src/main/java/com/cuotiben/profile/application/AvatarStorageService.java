package com.cuotiben.profile.application;

import com.cuotiben.common.ApiException;
import com.cuotiben.profile.domain.ProfileAvatar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
public class AvatarStorageService {
    private static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp"
    );

    private final JdbcTemplate jdbc;
    private final Path root;
    private final long maxBytes;

    public AvatarStorageService(JdbcTemplate jdbc,
                                @Value("${app.storage.location:./data/uploads}") String location,
                                @Value("${app.storage.max-file-bytes:10485760}") long maxBytes) {
        this.jdbc = jdbc;
        this.root = Path.of(location).toAbsolutePath().normalize().resolve("avatars").normalize();
        this.maxBytes = maxBytes;
    }

    @Transactional
    public void upload(String userId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("请选择头像图片。");
        if (file.getSize() > maxBytes) throw ApiException.badRequest("头像图片不能超过10MB。");
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String extension = ALLOWED.get(mime);
        if (extension == null) throw ApiException.badRequest("头像仅支持JPG、PNG或WEBP图片。");

        String id = UUID.randomUUID().toString();
        String storageKey = id + extension;
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) throw ApiException.badRequest("头像存储路径不合法。");
        List<String> oldKeys = jdbc.query("SELECT avatar_storage_key FROM users WHERE id = ?",
                (rs, row) -> rs.getString(1), userId);
        if (oldKeys.isEmpty()) throw ApiException.notFound("用户档案不存在。");

        try {
            Files.createDirectories(root);
            file.transferTo(target);
            try {
                jdbc.update("""
                        UPDATE users SET avatar_storage_key = ?, avatar_mime_type = ?, updated_at = NOW(3)
                        WHERE id = ?
                        """, storageKey, mime, userId);
            } catch (RuntimeException exception) {
                Files.deleteIfExists(target);
                throw exception;
            }
            deleteFile(oldKeys.get(0), false);
        } catch (IOException exception) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AVATAR_UPLOAD_FAILED", "头像保存失败，请重试。");
        }
    }

    public ProfileAvatar load(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT avatar_storage_key AS storageKey, avatar_mime_type AS mimeType
                FROM users WHERE id = ?
                """, userId);
        if (rows.isEmpty() || rows.get(0).get("storageKey") == null) {
            throw ApiException.notFound("还没有设置头像。");
        }
        Map<String, Object> row = rows.get(0);
        Path path = resolve(String.valueOf(row.get("storageKey")));
        if (!Files.exists(path)) throw ApiException.notFound("头像文件不存在。");
        try {
            return new ProfileAvatar(path, String.valueOf(row.get("mimeType")), Files.size(path));
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AVATAR_READ_FAILED", "头像读取失败。");
        }
    }

    public void deleteForOwner(String userId) {
        List<String> keys = jdbc.query("SELECT avatar_storage_key FROM users WHERE id = ?",
                (rs, row) -> rs.getString(1), userId);
        if (!keys.isEmpty()) deleteFile(keys.get(0), true);
    }

    private void deleteFile(String key, boolean required) {
        if (key == null || key.isBlank()) return;
        Path path = resolve(key);
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            if (required) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AVATAR_DELETE_FAILED", "头像清理失败，账号数据未删除。");
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw ApiException.forbidden("头像存储路径校验失败。");
        return path;
    }
}
