package com.okf.rag.web;

import com.okf.rag.ingest.IngestionService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class IngestController {

    private final IngestionService ingestionService;

    public IngestController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Upload a PDF, DOCX, PPTX or text file. It is chunked, distilled into draft
     * OKF concepts, written into the bundle and re-indexed.
     */
    @PostMapping("/ingest")
    public IngestionService.IngestResult ingest(@RequestParam("file") MultipartFile file) {
        return ingestionService.ingest(file);
    }
}
