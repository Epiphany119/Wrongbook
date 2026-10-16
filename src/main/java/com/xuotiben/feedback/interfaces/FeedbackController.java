package com.xuotiben.feedback.interfaces;

import com.xuotiben.feedback.application.FeedbackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {
    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, String> submit(@RequestAttribute("userId") String userId,
                                      @RequestBody FeedbackService.FeedbackCommand command) {
        service.submit(userId, command);
        return Map.of("message", "反馈已保存，感谢你的建议。");
    }
}
