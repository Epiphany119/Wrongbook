package com.xuotiben.profile.interfaces;

import com.xuotiben.profile.application.ProfileService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
