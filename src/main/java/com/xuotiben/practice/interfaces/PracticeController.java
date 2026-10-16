package com.xuotiben.practice.interfaces;

import com.xuotiben.practice.application.PracticeService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/practice-sessions")
public class PracticeController {
    private final PracticeService service;

    public PracticeController(PracticeService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, Object> start(@RequestAttribute("userId") String userId,
                                     @RequestBody PracticeService.PracticeCommand command) {
        return service.start(userId, command);
    }

    @PostMapping("/{sessionId}/answers")
    public Map<String, Object> submit(@RequestAttribute("userId") String userId,
                                      @PathVariable String sessionId,
                                      @RequestBody PracticeService.AnswersCommand command) {
        return service.submit(userId, sessionId, command == null ? List.of() : command.answers());
    }
}
