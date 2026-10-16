package com.cuotiben.identity.interfaces;

import com.cuotiben.identity.application.DevAuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class DevAuthController {
    private final DevAuthService service;

    public DevAuthController(DevAuthService service) {
        this.service = service;
    }

    @PostMapping("/dev-login")
    public Map<String, Object> devLogin(@RequestBody DevAuthService.LoginCommand command) {
        return service.login(command);
    }
}
