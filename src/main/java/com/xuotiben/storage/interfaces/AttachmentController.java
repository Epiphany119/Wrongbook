package com.xuotiben.storage.interfaces;

import com.xuotiben.storage.application.AttachmentStorageService;
import com.xuotiben.storage.domain.AttachmentFile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AttachmentController {
    private final AttachmentStorageService storage;

    public AttachmentController(AttachmentStorageService storage) {
        this.storage = storage;
    }

    @PostMapping(value = "/questions/{questionId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestAttribute("userId") String userId,
                                      @PathVariable String questionId,
                                      @RequestHeader(value = "X-Upload-Request-ID", required = false) String requestId,
                                      @RequestPart("file") MultipartFile file) {
        return storage.upload(userId, questionId, requestId, file);
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<Resource> download(@RequestAttribute("userId") String userId, @PathVariable String id) {
        AttachmentFile file = storage.load(userId, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .contentLength(file.byteSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file.path()));
    }
}
