package io.github.astatine202.scras.backend.service;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

@Service
@Slf4j
public class FileStorageService {
    private final Path inputPath = Paths.get("temp/input");
    private final Path outputPath = Paths.get("temp/output");
    private final Path bufferPath = Paths.get("temp/buffer");

    @SneakyThrows
    public void init() {
        Files.createDirectories(inputPath);
        Files.createDirectories(outputPath);
        Files.createDirectories(bufferPath);
    }

    @SneakyThrows
    public String save(MultipartFile file) {
        Files.createDirectories(inputPath);
        cleanDirectory(inputPath);
        Files.copy(file.getInputStream(),
                this.inputPath.resolve(file.getOriginalFilename()));
        return file.getOriginalFilename();
    }

    @PostConstruct // 启动时执行
    @PreDestroy // 关闭时执行
    public void cleanTempDirectories() {
        cleanDirectory(inputPath);
        cleanDirectory(outputPath);
        cleanDirectory(bufferPath);
    }

    private void cleanDirectory(Path path) {
        if (!Files.exists(path))
            return;

        try (Stream<Path> files = Files.list(path)) {
            files.forEach(file -> {
                try {
                    if (Files.isDirectory(file)) {
                        cleanDirectory(file);
                    }
                    Files.delete(file);
                } catch (IOException e) {
                    log.error("删除文件失败: {}", file);
                }
            });
        } catch (IOException e) {
            log.error("清理目录失败: {}", path);
        }
    }
}