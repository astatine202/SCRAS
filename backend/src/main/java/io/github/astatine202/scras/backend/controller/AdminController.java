package io.github.astatine202.scras.backend.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.astatine202.scras.backend.service.FileStorageService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class AdminController {
    private final FileStorageService fileService;

    @PostMapping("/shutdown")
    public String shutdown() {
        fileService.cleanTempDirectories();
        System.exit(0);
        return "服务已停止";
    }
}
