package com.cuotiben.practice.interfaces;

import com.cuotiben.practice.application.PracticePaperService;
import com.cuotiben.practice.application.PracticeService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/practice-sessions")
public class PracticeController {
    private static final MediaType DOCX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final PracticeService service;
    private final PracticePaperService paperService;

    public PracticeController(PracticeService service, PracticePaperService paperService) {
        this.service = service;
        this.paperService = paperService;
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

    @PostMapping("/paper")
    public ResponseEntity<byte[]> exportPaper(@RequestAttribute("userId") String userId,
                                              @RequestBody(required = false) PracticePaperService.PaperCommand command) {
        PracticePaperService.GeneratedPaper paper = paperService.exportDocx(userId, command);
        return ResponseEntity.ok()
                .contentType(DOCX_MEDIA_TYPE)
                .contentLength(paper.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(paper.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(paper.bytes());
    }
}
