package io.github.astatine202.scras.backend.controller;

import io.github.astatine202.scras.backend.model.SliceResult;
import io.github.astatine202.scras.backend.service.FileStorageService;
import io.github.astatine202.scras.backend.service.SlicingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequiredArgsConstructor
public class SlicingController {
    private final FileStorageService storageService;
    private final SlicingService slicingService;

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        storageService.init();
        String filename = storageService.save(file);
        return ResponseEntity.ok(filename);
    }

    @GetMapping("/slice")
    public ResponseEntity<SliceResult> getSlice(
        @RequestParam String filename,
        @RequestParam String variable,
        @RequestParam String function
    ) {
        try {
            List<Integer> lines = slicingService.processSlice(filename, variable, function);
            return ResponseEntity.ok(new SliceResult(variable, function, lines));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}