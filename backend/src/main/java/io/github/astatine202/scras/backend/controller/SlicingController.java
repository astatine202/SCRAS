package io.github.astatine202.scras.backend.controller;

import io.github.astatine202.scras.backend.model.SliceProject;
import io.github.astatine202.scras.backend.model.SliceResult;
import io.github.astatine202.scras.backend.service.FileStorageService;
import io.github.astatine202.scras.backend.service.ProjectService;
import io.github.astatine202.scras.backend.service.SlicingService;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequiredArgsConstructor
public class SlicingController {
    private final FileStorageService storageService;
    private final SlicingService slicingService;
    private final ProjectService projectService;

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam MultipartFile file) {
        storageService.init();
        String filename = storageService.save(file);
        return ResponseEntity.ok(filename);
    }

    @PostMapping("/uploadProject")
    public ResponseEntity<Map<String, Object>> uploadProject(@RequestParam MultipartFile[] files) throws IOException {
        storageService.init();
        Map<String, Object> result = projectService.handleProjectUpload(files);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/getFileContent")
    public ResponseEntity<String> getFileContent(@RequestParam String path) {
        try {
            Path filePath = Paths.get("temp/input", path);
            String content = Files.readString(filePath);
            return ResponseEntity.ok(content);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("无法读取文件内容");
        }
    }

    @GetMapping("/slice")
    public ResponseEntity<SliceResult> getSlice(
            @RequestParam String filename,
            @RequestParam String variable,
            @RequestParam String function) {
        try {
            List<Integer> lines = slicingService.processSlice(filename, variable, function);
            return ResponseEntity.ok(new SliceResult(variable, function, lines));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/sliceProject")
    public ResponseEntity<SliceProject> getSliceProject(
            @RequestParam String projectName,
            @RequestParam String variable,
            @RequestParam String function,
            @RequestParam String filename) {
        try {
            Map<String, List<Integer>> lines = slicingService.processSlice(filename,
                    variable, function, projectName);
            System.out.println(lines);
            return ResponseEntity.ok(new SliceProject(variable, function, filename, lines));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}