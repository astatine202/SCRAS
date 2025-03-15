package io.github.astatine202.scras.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ProjectService {

    public Map<String, Object> handleProjectUpload(MultipartFile[] files) throws IOException {
        String originalprojectname = files[0].getOriginalFilename();
        if (originalprojectname == null) {
            throw new IOException("File original filename is null");
        }
        String projectName = originalprojectname.split("/")[0];
        Path projectDir = Paths.get("temp/input", projectName);
        Files.createDirectories(projectDir);

        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                throw new IOException("File original filename is null");
            }
            Path filePath = projectDir.resolve(originalFilename.substring(projectName.length() + 1));
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, file.getBytes());
        }

        Map<String, Object> result = Map.of(
                "fileTree", buildFileTree(projectDir),
                "allTotalLineCount", calculateTotalLineCount(projectDir));

        return result;
    }

    private List<Map<String, String>> buildFileTree(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> Map.of(
                            "name", path.getFileName().toString(),
                            "path", dir.relativize(path).toString()))
                    .collect(Collectors.toList());
        }
    }

    private int calculateTotalLineCount(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".c") /*|| path.toString().endsWith(".cpp")*/)
                    .mapToInt(path -> {
                        try {
                            return Files.readAllLines(path).size();
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        }
    }
}