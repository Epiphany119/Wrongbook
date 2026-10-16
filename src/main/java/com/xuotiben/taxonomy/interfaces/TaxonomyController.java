package com.xuotiben.taxonomy.interfaces;

import com.xuotiben.taxonomy.application.TaxonomyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/taxonomy")
public class TaxonomyController {
    private final TaxonomyService service;

    public TaxonomyController(TaxonomyService service) {
        this.service = service;
    }

    @GetMapping("/subjects")
    public List<Map<String, Object>> subjects() {
        return service.subjects();
    }

    @GetMapping("/chapters")
    public List<Map<String, Object>> chapters(@RequestAttribute("userId") String userId,
                                               @RequestParam String subjectId,
                                               @RequestParam(required = false) String grade) {
        return service.chapters(userId, subjectId, grade);
    }

    @GetMapping("/knowledge-points")
    public List<Map<String, Object>> knowledgePoints(@RequestAttribute("userId") String userId,
                                                      @RequestParam String chapterId) {
        return service.knowledgePoints(userId, chapterId);
    }

    @PostMapping("/chapters")
    public Map<String, Object> createChapter(@RequestAttribute("userId") String userId,
                                             @RequestBody TaxonomyService.ChapterCommand command) {
        return service.createChapter(userId, command);
    }

    @PostMapping("/knowledge-points")
    public Map<String, Object> createKnowledgePoint(@RequestAttribute("userId") String userId,
                                                     @RequestBody TaxonomyService.KnowledgePointCommand command) {
        return service.createKnowledgePoint(userId, command);
    }
}
