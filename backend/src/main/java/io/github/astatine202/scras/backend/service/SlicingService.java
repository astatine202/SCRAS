package io.github.astatine202.scras.backend.service;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

@Service
@Slf4j
public class SlicingService {
    private static final Pattern LINE_PATTERN = Pattern
            .compile("(\\w+)(?:@(\\w+))?\\s+\\{\".*?: \\[(.*?)\\]\"}");

    private Path getOutputPath(String filename, String suffix) {
        return Paths.get("temp/output",
                filename.replaceFirst("\\.[^.]+$", suffix));
    }

    @SneakyThrows
    public List<Integer> processSlice(String filename, String var, String func) {
        Path outputPath = getOutputPath(filename, "_Fwd.txt");

        if (!Files.exists(outputPath)) {
            runSlicingTool(filename);
        }

        return parseSliceResult(outputPath, var, func);
    }

    @SneakyThrows
    private void runSlicingTool(String filename) {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "--rm", "-v",
                Paths.get("").toAbsolutePath().toString() + ":/workspace",
                "nuptzyz/llvm-slicing", "llvm-slicing",
                "/workspace/temp/input/" + filename, "-d", "Fwd",
                "-m", "IFDS");

        pb.redirectOutput(ProcessBuilder.Redirect.to(
                new File(getOutputPath(filename, "_Fwd.txt").toString())));
        pb.redirectError(ProcessBuilder.Redirect.to(
                new File(getOutputPath(filename, "_Fwd_error.txt").toString())));
        Process process = pb.start();
        if (process.waitFor() != 0) {
            throw new RuntimeException("Slicing failed");
        }
        if (filename.endsWith(".cpp")) {
            demangleResultFile(filename);
        }
    }

    @SneakyThrows
    private void demangleResultFile(String filename) {
        Path rawPath = getOutputPath(filename, "_Fwd.txt");
        Path demangledPath = getOutputPath(filename, "_Fwd_demangled.txt");

        // 使用c++filt进行反修饰
        ProcessBuilder pb = new ProcessBuilder("c++filt");
        pb.redirectInput(ProcessBuilder.Redirect.from(rawPath.toFile()));
        pb.redirectOutput(ProcessBuilder.Redirect.to(demangledPath.toFile()));
        Process process = pb.start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Demangling failed");
        }

        // 清空原文件
        Files.newBufferedWriter(rawPath, StandardOpenOption.TRUNCATE_EXISTING).close();
        // 去掉参数列表
        removeParameterList(demangledPath, rawPath);
        // 删除反修饰文件
        // Files.delete(demangledPath);
    }

    @SneakyThrows
    private void removeParameterList(Path demangledPath, Path outputPath) {
        try (BufferedReader reader = Files.newBufferedReader(demangledPath);
                BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 使用正则表达式去掉参数列表
                String modifiedLine = line.replaceAll("(\\w+@\\w+)\\(.*?\\)", "$1");
                writer.write(modifiedLine);
                writer.newLine();
            }
        }
    }

    @SneakyThrows
    private List<Integer> parseSliceResult(Path path, String var, String func) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return reader.lines()
                    .map(LINE_PATTERN::matcher)
                    .filter(Matcher::find)
                    .filter(m -> m.group(1).equals(var) && (m.group(2) == null ? "" : m.group(2)).equals(func))
                    .flatMap(m -> Arrays.stream(m.group(3).split(",")))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .toList();
        }
    }
}