package com.cuotiben.review.interfaces;

import com.cuotiben.review.application.ReviewService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/questions/{questionId}/reviews")
public class ReviewController {
    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, Object> review(@RequestAttribute("userId") String userId,
                                      @PathVariable String questionId,
                                      @RequestBody ReviewService.ReviewCommand command) {
        return service.record(userId, questionId, command, null);
    }
}
