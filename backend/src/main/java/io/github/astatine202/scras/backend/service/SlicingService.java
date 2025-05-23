package io.github.astatine202.scras.backend.service;

import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Scope("prototype")
@Slf4j
public class SlicingService {
    private static final String IMAGE = "nuptzyz/llvm-slicing";
    private static final String LLVM_SLICING = "llvm-slicing";
    private static final String LLVM_LINK = "llvm-link-3.3";

    private final String TEMP = "temp";
    private static final String INPUT = "input";
    private static final String OUTPUT = "output";
    private static final String BUFFER = "buffer";
    private final String PATH_INPUT = Paths.get(TEMP, INPUT).toString();
    private final String PATH_OUTPUT = Paths.get(TEMP, OUTPUT).toString();
    private final String PATH_BUFFER = Paths.get(TEMP, BUFFER).toString();

    private static final String FILE_LL = "output.ll";
    private static final String FILE_FWD = "output_Fwd.txt";
    private static final String FILE_DOT = "callgraph.dot";

    private final Path LLPATH = Paths.get(PATH_OUTPUT, FILE_LL);
    private final Path FWDPATH = Paths.get(PATH_OUTPUT, FILE_FWD);
    private final Path DOTPATH = Paths.get(PATH_OUTPUT, FILE_DOT);
    private final Path CFGPATH = Paths.get(PATH_OUTPUT, "cfg");

    private static final Pattern LINE_PATTERN = Pattern
            .compile("(\\w+)(?:@(\\w+))?\\s+\\{\".*?: \\[(.*?)\\]\"}");
    private static final Pattern MULTIFILE_PATTERN = Pattern.compile(
            "(\\w+)(?:@(\\w+))?\\s+\\{([^}]+)\\}",
            Pattern.DOTALL);

    @Data
    private static class SliceInfo {
        private final String var;
        private final String func;
        private final Map<String, List<Integer>> results;
    }

    @Data
    private static class NodeInfo {
        private final String nodeId;
        private final int depth;
        private final int callCount;
        private final int fanOut;
        private final int mccabeComplexity;
    }

    private final Map<String, NodeInfo> nodeMap = new HashMap<>();
    private final Map<String, Set<String>> callGraph = new HashMap<>();

    private Path getOutputPath(String filename, String suffix) {
        return Paths.get(PATH_OUTPUT,
                filename.replaceFirst("\\.[^.]+$", suffix));
    }

    @SneakyThrows
    public List<Integer> processSlice(String filename, String var, String func) {
        Path inputPath = Paths.get(PATH_INPUT, filename);
        if (!Files.exists(FWDPATH)) {
            compileToLLVM(inputPath, LLPATH);
            runSlicingTool(OUTPUT, FILE_LL);
        }
        return parseSliceResult(FWDPATH, var, func);
    }

    @SneakyThrows
    public Map<String, List<Integer>> processSlice(String fileName,
            String var, String func, String projectName) {
        if (!Files.exists(FWDPATH)) {
            processProject(projectName);
            runSlicingTool(OUTPUT, FILE_LL);
            fwdParser(FWDPATH, projectName);
        }
        return parseSliceResult(FWDPATH, var, func, projectName);
    }

    @SneakyThrows
    private void runSlicingTool(String dir, String filename) {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "--rm", "-v",
                Paths.get("").toAbsolutePath().toString() + ":/workspace",
                IMAGE, LLVM_SLICING,
                "/workspace/temp/" + dir + "/" + filename, "-d", "Fwd");

        pb.redirectOutput(ProcessBuilder.Redirect.to(
                new File(getOutputPath(filename, "_Fwd.txt").toString())));
        pb.redirectError(ProcessBuilder.Redirect.to(
                new File(getOutputPath(filename, "_Fwd_error.txt").toString())));
        Process process = pb.start();
        if (process.waitFor() != 0) {
            throw new RuntimeException("Slicing failed");
        }
        dot();
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

    @SneakyThrows
    private void fwdParser(Path resultPath, String projectName) {
        // 定义正则表达式匹配每一行条目
        final Pattern entryPattern = Pattern.compile(
                "(\\w+)(?:@(\\w+))?\\s+\\{(\".*?\".*?)}");

        List<SliceInfo> sliceInfoList = new ArrayList<>();

        System.out.println("Parsing slice result from: " + resultPath);

        try (BufferedReader reader = Files.newBufferedReader(resultPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = entryPattern.matcher(line);
                if (matcher.find()) {
                    String var = matcher.group(1); // 变量名
                    String func = matcher.group(2) == null ? "" : matcher.group(2); // 函数名，若无则为空字符串
                    String rawResults = matcher.group(3); // 原始结果内容

                    // 解析结果内容
                    Map<String, List<Integer>> results = Arrays.stream(rawResults.split(",\\s*(?=\")"))
                            .map(String::trim)
                            .map(entry -> entry.replaceAll("\"", "")) // 去掉双引号
                            .map(entry -> entry.split(":\\s*\\[")) // 分割路径和行号
                            .filter(parts -> parts.length == 2)
                            .collect(Collectors.toMap(
                                    parts -> parts[0].replaceFirst(".*/" + Pattern.quote(projectName) + "/", ""), // 过滤掉前缀
                                    parts -> Arrays.stream(parts[1].replace("]", "").split(",\\s*")) // 解析行号
                                            .map(Integer::parseInt)
                                            .collect(Collectors.toList())));

                    // 创建 SliceInfo 对象并添加到列表
                    sliceInfoList.add(new SliceInfo(var, func, results));
                }
            }
        }

        System.out.println("Parsed slice info: " + sliceInfoList.size() + " entries.");

        // 将结果输出为 JSON 文件
        ObjectMapper objectMapper = new ObjectMapper();
        Path jsonFilePath = Paths.get(PATH_OUTPUT, "slice_info.json");
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(jsonFilePath.toFile(), sliceInfoList);

        System.out.println("Slice info saved to: " + jsonFilePath);
    }

    // 多文件的处理
    @SneakyThrows
    public void processProject(String projectName) {
        Path inputDir = Paths.get(PATH_INPUT, projectName);
        Path bufferDir = Paths.get(PATH_BUFFER, projectName);
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

        // 查找所有C文件并编译为LLVM IR
        try (Stream<Path> files = Files.walk(inputDir)) {
            files.filter(p -> p.toString().endsWith(".c")
            /* || p.toString().endsWith(".cpp") */)
                    .forEach(cFilePath -> {
                        Path relative = inputDir.relativize(cFilePath);
                        Path llFilePath = bufferDir.resolve(relative.toString()
                                /* .replaceFirst("\\.cpp$", ".ll") */
                                .replaceFirst("\\.c$", ".ll"));
                        compileToLLVM(cFilePath, llFilePath);
                    });
        }

        llvm_link(bufferDir, LLPATH);
    }

    @SneakyThrows
    private void compileToLLVM(Path cFilePath, Path llFilePath) {
        Path c = Paths.get("/workspace", cFilePath.toString());
        Path l = Paths.get("/workspace", llFilePath.toString());
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "--rm", "-v",
                Paths.get("").toAbsolutePath() + ":/workspace",
                IMAGE, "clang", "-emit-llvm", "-S", "-O0", "-g",
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
                IMAGE, LLVM_LINK, "-S"));
        command.addAll(llFiles);
        command.addAll(Arrays.asList("-o", l.toString().replace("\\", "/")));

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        if (process.waitFor() != 0) {
            System.out.println("\n" + pb.command());
            throw new RuntimeException("Slicing failed");
        }
    }

    @SneakyThrows
    private void dot() {
        System.out.println("Generating call graph from: " + LLPATH);
        // 调用 opt 工具生成CG & CFG
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "--rm", "-v",
                Paths.get("").toAbsolutePath().toString() + ":/workspace",
                IMAGE, "/bin/bash", "-c",
                "cd /workspace && cd " + PATH_OUTPUT.replace("\\", "/")
                        + " && opt -dot-callgraph " + FILE_LL + " -S -o " + FILE_LL
                        + " && dot -Tpng " + FILE_DOT + " -o callgraph.png"
                        + " && mkdir cfg && cd cfg && opt -dot-cfg ../" + FILE_LL);
        Process process = pb.start();

        // opt -dot-callgraph output.ll
        // 会不可避免地在控制台进行标准输出, 从而造成缓冲区阻塞, 需要疏通
        try (BufferedReader stdOut = new BufferedReader(new InputStreamReader(process.getInputStream()));
                BufferedReader stdErr = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                BufferedWriter outputWriter = Files.newBufferedWriter(Paths.get(PATH_OUTPUT, "process_stdout.log"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                BufferedWriter errorWriter = Files.newBufferedWriter(Paths.get(PATH_OUTPUT, "process_stderr.log"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            // 将标准输出写入文件
            stdOut.lines().forEach(line -> {
                try {
                    outputWriter.write(line);
                    outputWriter.newLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            // 将错误输出写入文件
            stdErr.lines().forEach(line -> {
                try {
                    errorWriter.write(line);
                    errorWriter.newLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        if (process.waitFor() != 0) {
            throw new RuntimeException("Failed to generate call graph");
        }
        System.out.println("Call graph generated: " + DOTPATH);
        // 解析生成的调用图
        dotParser(DOTPATH);
        System.out.println("Node Map:");
        nodeMap.forEach((key, value) -> System.out.println(key + " = " + value));
    }

    @SneakyThrows
    private void dotParser(Path dotPath) {
        Map<String, Integer> callCountMap = new HashMap<>();
        Map<String, Integer> fanOutMap = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(dotPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.matches("Node\\w+ \\[.*label=.*\\];")) {
                    String nodeId = line.substring(0, line.indexOf(" "));
                    String label = line.replaceAll(".*label=\"\\{(.*?)\\}\".*", "$1");
                    nodeMap.put(label, new NodeInfo(nodeId, -1, 0, 0, 0));
                } else if (line.matches("Node\\w+ -> Node\\w+;")) {
                    String[] parts = line.split(" -> ");
                    String fromNode = parts[0].trim();
                    String toNode = parts[1].replace(";", "").trim();
                    callGraph.computeIfAbsent(fromNode, k -> new HashSet<>()).add(toNode);
                    callCountMap.put(toNode, callCountMap.getOrDefault(toNode, 0) + 1);
                    fanOutMap.put(fromNode, fanOutMap.getOrDefault(fromNode, 0) + 1);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse dot file", e);
        }

        Map<String, Integer> mccabeComplexityMap = calMcCabeComplexity(CFGPATH);

        // 计算所有节点的深度、被调用数、扇出度和McCabe复杂度
        for (Map.Entry<String, NodeInfo> entry : nodeMap.entrySet()) {
            String functionName = entry.getKey();
            NodeInfo nodeInfo = entry.getValue();
            int depth = calculateDepth(nodeInfo.getNodeId(), 0, new HashSet<>());
            int callCount = callCountMap.getOrDefault(nodeInfo.getNodeId(), 0);
            int fanOut = fanOutMap.getOrDefault(nodeInfo.getNodeId(), 0);
            int mccabeComplexity = mccabeComplexityMap.getOrDefault(functionName, 0);
            nodeMap.put(functionName, new NodeInfo(
                    nodeInfo.getNodeId(),
                    depth, callCount, fanOut, mccabeComplexity));
        }
    }

    private int calculateDepth(String nodeId, int depth, Set<String> visited) {
        // 检查是否已经访问过当前节点
        if (visited.contains(nodeId)) {
            // 如果存在循环调用，返回当前深度
            return depth;
        }

        // 将当前节点标记为已访问
        visited.add(nodeId);

        Set<String> children = callGraph.get(nodeId);
        if (children == null || children.isEmpty()) {
            return depth;
        }

        // 递归计算子节点的最大深度
        return children.stream()
                .mapToInt(child -> calculateDepth(child, depth + 1, visited))
                .max()
                .orElse(depth);
    }

    @SneakyThrows
    public Map<String, Integer> calMcCabeComplexity(Path cfgFolderPath) {
        Map<String, Integer> complexityMap = new HashMap<>();

        // 遍历 cfg 文件夹中的所有 .dot 文件
        try (Stream<Path> paths = Files.walk(cfgFolderPath)) {
            List<Path> dotFiles = paths.filter(p -> p.toString().endsWith(".dot")).collect(Collectors.toList());

            for (Path dotFile : dotFiles) {
                String functionName = dotFile.getFileName().toString()
                        .replace("cfg.", "")
                        .replace(".dot", "");
                int basicBlockCount = 0; // N
                int edgeCount = 0; // E

                try (BufferedReader reader = Files.newBufferedReader(dotFile)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.matches("Node\\w+ \\[.*\\];")) {
                            basicBlockCount++;
                        } else if (line.matches("Node\\w+(?::\\w+)? -> Node\\w+(?::\\w+)?;")) {
                            edgeCount++;
                        }
                    }
                }
                // McCabe复杂度公式：E - N + 2P
                int complexity = edgeCount - basicBlockCount + 2; // P = 1
                complexityMap.put(functionName, complexity);
            }
        }
        return complexityMap;
    }

}