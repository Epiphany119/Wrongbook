package com.cuotiben.question.interfaces;

import com.cuotiben.question.application.QuestionService;
import com.cuotiben.question.domain.QuestionRecord;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class QuestionController {
    private final QuestionService service;

    public QuestionController(QuestionService service) {
        this.service = service;
    }

    @GetMapping("/questions")
    public Map<String, Object> list(@RequestAttribute("userId") String userId,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String subjectId,
                                    @RequestParam(required = false) String chapterId,
                                    @RequestParam(required = false) String dateFrom,
                                    @RequestParam(required = false) String dateTo,
                                    @RequestParam(required = false) String knowledgePointId,
                                    @RequestParam(required = false) String tag,
                                    @RequestParam(required = false) String errorReason,
                                    @RequestParam(required = false) String state,
                                    @RequestParam(required = false) Boolean dueOnly,
                                    @RequestParam(defaultValue = "NEWEST") String sort,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int pageSize) {
        return service.list(userId, keyword, subjectId, chapterId, dateFrom, dateTo, knowledgePointId, tag, errorReason,
                state, dueOnly, sort, page, pageSize);
    }

    @GetMapping("/questions/trash")
    public List<QuestionRecord> trash(@RequestAttribute("userId") String userId) {
        return service.trash(userId);
    }

    @PostMapping("/questions")
    public QuestionRecord create(@RequestAttribute("userId") String userId,
                                 @RequestBody QuestionService.QuestionCommand command) {
        return service.create(userId, command);
    }

    @GetMapping("/questions/{id}")
    public Map<String, Object> detail(@RequestAttribute("userId") String userId, @PathVariable String id) {
        return service.detail(userId, id);
    }

    @GetMapping("/questions/{id}/reflections")
    public List<?> reflections(@RequestAttribute("userId") String userId, @PathVariable String id) {
        return service.reflections(userId, id);
    }

    @PostMapping("/questions/{id}/reflections")
    public Object addReflection(@RequestAttribute("userId") String userId, @PathVariable String id,
                                @RequestBody QuestionService.ReflectionCommand command) {
        return service.addReflection(userId, id, command);
    }

    @PutMapping("/questions/{id}")
    public QuestionRecord update(@RequestAttribute("userId") String userId, @PathVariable String id,
                                 @RequestBody QuestionService.QuestionCommand command) {
        return service.update(userId, id, command);
    }

    @DeleteMapping("/questions/{id}")
    public Map<String, String> delete(@RequestAttribute("userId") String userId, @PathVariable String id) {
        service.delete(userId, id);
        return Map.of("message", "错题已移入回收站。");
    }

    @PostMapping("/questions/{id}/archive")
    public QuestionRecord archive(@RequestAttribute("userId") String userId, @PathVariable String id) {
        return service.archive(userId, id);
    }

    @PostMapping("/questions/{id}/restore")
    public QuestionRecord restore(@RequestAttribute("userId") String userId, @PathVariable String id) {
        return service.restore(userId, id);
    }
}
