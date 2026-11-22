package com.cuotiben.practice.interfaces;

import com.cuotiben.practice.application.PracticePaperService;
import com.cuotiben.practice.application.PracticeService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PracticeController {
    private static final MediaType DOCX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final PracticeService service;
    private final PracticePaperService paperService;

    public PracticeController(PracticeService service, PracticePaperService paperService) {
        this.service = service;
        this.paperService = paperService;
    }

    @PostMapping("/practice-sessions")
    public Map<String, Object> start(@RequestAttribute("userId") String userId,
                                     @RequestBody PracticeService.PracticeCommand command) {
        return service.start(userId, command);
    }

    @PostMapping("/practice-sessions/{sessionId}/answers")
    public Map<String, Object> submit(@RequestAttribute("userId") String userId,
                                      @PathVariable String sessionId,
                                      @RequestBody PracticeService.AnswersCommand command) {
        return service.submit(userId, sessionId, command == null ? List.of() : command.answers());
    }

    @PostMapping("/practice-sessions/paper")
    public ResponseEntity<byte[]> exportPaper(@RequestAttribute("userId") String userId,
                                              @RequestBody(required = false) PracticePaperService.PaperCommand command) {
        PracticePaperService.GeneratedPaper paper = paperService.exportDocx(userId, command);
        return docxResponse(paper);
    }

    @GetMapping("/practice-papers")
    public List<Map<String, Object>> listPapers(@RequestAttribute("userId") String userId,
                                                @RequestParam(required = false) String keyword) {
        return paperService.listRecords(userId, keyword);
    }

    @PostMapping("/practice-papers")
    public Map<String, Object> createPaper(@RequestAttribute("userId") String userId,
                                           @RequestBody PracticePaperService.SavePaperCommand command) {
        return paperService.createRecord(userId, command);
    }

    @PutMapping("/practice-papers/{id}")
    public Map<String, Object> renamePaper(@RequestAttribute("userId") String userId,
                                           @PathVariable String id,
                                           @RequestBody PracticePaperService.RenamePaperCommand command) {
        return paperService.renameRecord(userId, id, command);
    }

    @DeleteMapping("/practice-papers/{id}")
    public Map<String, String> deletePaper(@RequestAttribute("userId") String userId,
                                           @PathVariable String id) {
        paperService.deleteRecord(userId, id);
        return Map.of("message", "组卷记录已删除。");
    }

    @PostMapping("/practice-papers/{id}/export")
    public ResponseEntity<byte[]> exportSavedPaper(@RequestAttribute("userId") String userId,
                                                   @PathVariable String id,
                                                   @RequestBody(required = false) PracticePaperService.ExportPaperCommand command) {
        String mode = command == null ? "WITH_ANSWERS" : command.mode();
        PracticePaperService.GeneratedPaper paper = paperService.exportSavedPaper(userId, id, mode);
        return docxResponse(paper);
    }

    private ResponseEntity<byte[]> docxResponse(PracticePaperService.GeneratedPaper paper) {
        return ResponseEntity.ok()
                .contentType(DOCX_MEDIA_TYPE)
                .contentLength(paper.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(paper.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(paper.bytes());
    }
}
