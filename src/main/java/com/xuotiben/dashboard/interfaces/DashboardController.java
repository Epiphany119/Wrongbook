package com.xuotiben.dashboard.interfaces;

import com.xuotiben.dashboard.application.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> overview(@RequestAttribute("userId") String userId,
                                        @RequestParam(defaultValue = "7") int days) {
        return service.overview(userId, Math.max(1, Math.min(90, days)));
    }
}
