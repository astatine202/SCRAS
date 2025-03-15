package io.github.astatine202.scras.backend.service;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class SlicingService {
    private static final Pattern LINE_PATTERN = Pattern
            .compile("(\\w+)(?:@(\\w+))?\\s+\\{\".*?: \\[(.*?)\\]\"}");
    private static final Pattern MULTIFILE_PATTERN = Pattern.compile(
            "(\\w+)(?:@(\\w+))?\\s+\\{([^}]+)\\}",
            Pattern.DOTALL);

    private Path getOutputPath(String filename, String suffix) {
        return Paths.get("temp/output",
                filename.replaceFirst("\\.[^.]+$", suffix));
    }

    @SneakyThrows
    public List<Integer> processSlice(String filename, String var, String func) {
        Path outputPath = getOutputPath(filename, "_Fwd.txt");

        if (!Files.exists(outputPath)) {
            runSlicingTool("input", filename);
        }

        return parseSliceResult(outputPath, var, func);
    }

    @SneakyThrows
    public Map<String, List<Integer>> processSlice(String fileName,
            String var, String func, String projectName) {
        Path outputPath = getOutputPath(projectName + ".ll", "_Fwd.txt");

        if (!Files.exists(outputPath)) {
            processProject(projectName);
        }

        return parseSliceResult(outputPath, var, func, projectName);
    }

    @SneakyThrows
    private void runSlicingTool(String dir, String filename) {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "--rm", "-v",
                Paths.get("").toAbsolutePath().toString() + ":/workspace",
                "nuptzyz/llvm-slicing", "llvm-slicing",
                "/workspace/temp/" + dir + "/" + filename, "-d", "Fwd",
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

        // 清空原文件、去掉参数列表
        /*
         * Files.newBufferedWriter(rawPath,
         * StandardOpenOption.TRUNCATE_EXISTING).close();
         * try (BufferedReader reader = Files.newBufferedReader(demangledPath);
         * BufferedWriter writer = Files.newBufferedWriter(rawPath)) {
         * String line;
         * while ((line = reader.readLine()) != null) {
         * // 使用正则表达式去掉参数列表
         * String modifiedLine = line.replaceAll("(\\w+@\\w+)\\(.*?\\)", "$1");
         * writer.write(modifiedLine);
         * writer.newLine();
         * }
         * }
         */
        // 删除反修饰文件
        // Files.delete(demangledPath);
    }

    @SneakyThrows
    private List<Integer> parseSliceResult(Path resultPath, String var, String func) {
        try (BufferedReader reader = Files.newBufferedReader(resultPath)) {
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

    @SneakyThrows
    private Map<String, List<Integer>> parseSliceResult(Path resultPath,
            String var, String func, String projectName) {

        // 改进后的条目匹配正则（处理多层路径）
        final Pattern entryPattern = Pattern.compile(
                "\"([^\"]*?" + Pattern.quote(projectName) + "/)?([^\":]+?):\\s*\\[(\\d+(?:,\\s*\\d+)*)\\]\"");

        try (BufferedReader reader = Files.newBufferedReader(resultPath)) {
            return reader.lines()
                    .map(MULTIFILE_PATTERN::matcher)
                    .filter(Matcher::find)
                    .filter(m -> m.group(1).equals(var) && (m.group(2) == null ? "" : m.group(2)).equals(func))
                    // 处理大括号内的所有条目（改进分割方式）
                    .flatMap(m -> Arrays.stream(m.group(3).split(",\\s*(?=\")")))
                    .map(String::trim)
                    .map(entry -> {
                        Matcher entryMatcher = entryPattern.matcher(entry);
                        return entryMatcher.find() ? entryMatcher : null;
                    })
                    .filter(Objects::nonNull)
                    // 转换路径格式（支持多层路径结构）
                    .collect(Collectors.groupingBy(
                            m -> m.group(2).replaceAll(".*?(" + Pattern.quote(projectName) + "/)?", ""),
                            Collectors.mapping(
                                    m -> Arrays.stream(m.group(3).split(",\\s*"))
                                            .map(Integer::parseInt)
                                            .collect(Collectors.toList()),
                                    Collectors.toList())))
                    // 合并结果（增加去重和排序）
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().stream()
                                    .flatMap(List::stream)
                                    .distinct()
                                    .sorted()
                                    .collect(Collectors.toList())));
        }
    }

    // 多文件的处理
    @SneakyThrows
    public void processProject(String projectName) {
        Path inputDir = Paths.get("temp/input", projectName);
        Path bufferDir = Paths.get("temp/buffer", projectName);
        Files.createDirectories(bufferDir);

        try (Stream<Path> paths = Files.walk(inputDir)) {
            paths.filter(Files::isDirectory)
                    .forEach(dir -> {
                        try {
                            Path targetDir = bufferDir.resolve(inputDir.relativize(dir));
                            Files.createDirectories(targetDir);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        // 查找所有C/C++文件并编译为LLVM IR
        try (Stream<Path> files = Files.walk(inputDir)) {
            files.filter(p -> p.toString().endsWith(".c")
                    || p.toString().endsWith(".cpp"))
                    .forEach(cFilePath -> {
                        Path relative = inputDir.relativize(cFilePath);
                        Path llFilePath = bufferDir.resolve(relative.toString()
                                .replaceFirst("\\.cpp$", ".ll")
                                .replaceFirst("\\.c$", ".ll"));
                        compileToLLVM(cFilePath, llFilePath);
                    });
        }

        Path linkedLLPath = Paths.get("temp/output", projectName + ".ll");
        llvm_link(bufferDir, linkedLLPath);
        runSlicingTool("output", projectName + ".ll");
    }

    @SneakyThrows
    private void compileToLLVM(Path cFilePath, Path llFilePath) {
        Path c = Paths.get("/workspace", cFilePath.toString());
        Path l = Paths.get("/workspace", llFilePath.toString());
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "--rm", "-v",
                Paths.get("").toAbsolutePath() + ":/workspace",
                "nuptzyz/llvm-slicing",
                "clang", "-emit-llvm", "-S", "-O0", "-g",
                c.toString().replace("\\", "/"),
                "-o",
                l.toString().replace("\\", "/"));

        Process process = pb.start();
        if (process.waitFor() != 0) {
            System.out.println("\n" + pb.command());
            throw new RuntimeException("Slicing failed");
        }
    }

    @SneakyThrows
    private void llvm_link(Path bufferDir, Path linkedLLPath) {
        Path l = Paths.get("/workspace", linkedLLPath.toString());
        List<String> llFiles = Files.walk(bufferDir)
                .filter(p -> p.toString().endsWith(".ll"))
                .map(p -> "/workspace/" + p.toString().replace("\\", "/"))
                .collect(Collectors.toList());

        List<String> command = new ArrayList<>(Arrays.asList(
                "docker", "run", "--rm", "-v",
                Paths.get("").toAbsolutePath() + ":/workspace",
                "nuptzyz/llvm-slicing",
                "llvm-link-3.3", "-S"));
        command.addAll(llFiles);
        command.addAll(Arrays.asList("-o", l.toString().replace("\\", "/")));

        ProcessBuilder pb = new ProcessBuilder(command);

        /*
         * ProcessBuilder pb = new ProcessBuilder(
         * "docker", "run", "--rm", "-v",
         * Paths.get("").toAbsolutePath() + ":/workspace",
         * "nuptzyz/llvm-slicing",
         * "llvm-link-3.3", "-S",
         * String.join(" ", llFiles).replace("\\", "/"),
         * "-o", l.toString().replace("\\", "/"));
         */
        Process process = pb.start();
        if (process.waitFor() != 0) {
            System.out.println("\n" + pb.command());
            throw new RuntimeException("Slicing failed");
        }
    }
}