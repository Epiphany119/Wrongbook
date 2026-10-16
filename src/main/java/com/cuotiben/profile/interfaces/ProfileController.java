package com.cuotiben.profile.interfaces;

import com.cuotiben.profile.application.ProfileService;
import com.cuotiben.profile.domain.ProfileAvatar;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {
    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> get(@RequestAttribute("userId") String userId) {
        return service.get(userId);
    }

    @PutMapping
    public Map<String, Object> update(@RequestAttribute("userId") String userId,
                                      @RequestBody ProfileService.ProfileCommand command) {
        return service.update(userId, command);
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadAvatar(@RequestAttribute("userId") String userId,
                                            @RequestPart("file") MultipartFile file) {
        return service.uploadAvatar(userId, file);
    }

    @GetMapping("/avatar")
    public ResponseEntity<Resource> getAvatar(@RequestAttribute("userId") String userId) {
        ProfileAvatar avatar = service.loadAvatar(userId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.mimeType()))
                .contentLength(avatar.byteSize())
                .body(new FileSystemResource(avatar.path()));
    }

    @GetMapping("/export")
    public Map<String, Object> export(@RequestAttribute("userId") String userId) {
        return service.export(userId);
    }

    @DeleteMapping
    public Map<String, String> deleteAccount(@RequestAttribute("userId") String userId) {
        service.deleteAccount(userId);
        return Map.of("message", "账号及其错题数据已删除。");
    }
}
