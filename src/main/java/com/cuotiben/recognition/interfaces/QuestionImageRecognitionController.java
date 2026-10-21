package com.cuotiben.recognition.interfaces;

import com.cuotiben.recognition.application.QuestionImageRecognitionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class QuestionImageRecognitionController {
    private final QuestionImageRecognitionService service;

    public QuestionImageRecognitionController(QuestionImageRecognitionService service) {
        this.service = service;
    }

    @PostMapping(value = "/recognition/question-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public QuestionImageRecognitionResponse recognizeQuestionImage(
            @RequestAttribute("userId") String userId,
            @RequestParam("file") MultipartFile file) {
        return service.recognize(file);
    }
}
