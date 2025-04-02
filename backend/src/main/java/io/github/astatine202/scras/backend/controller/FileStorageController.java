package io.github.astatine202.scras.backend.controller;

import io.github.astatine202.scras.backend.service.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/file")
public class FileStorageController {
    @Autowired
    private FileStorageService fileStorageService;

    @GetMapping("/cleanTempDirectories")
    public void cleanTempDirectories() {
        fileStorageService.cleanTempDirectories();
    }
}
