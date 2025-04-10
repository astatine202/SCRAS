package io.github.astatine202.scras.backend.service;

import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
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

    private static final Pattern LINE_PATTERN = Pattern
            .compile("(\\w+)(?:@(\\w+))?\\s+\\{\".*?: \\[(.*?)\\]\"}");
    private static final Pattern MULTIFILE_PATTERN = Pattern.compile(
            "(\\w+)(?:@(\\w+))?\\s+\\{([^}]+)\\}",
            Pattern.DOTALL);

    @Data
    private static class SliceInfo {
        private final String var;
        private final Map<String, List<Integer>> impactDomain;
    }

    @Data
    private static class NodeInfo {
        private final String nodeId;
        private final int depth;
    }

    private final Map<String, List<SliceInfo>> sliceInfoMap = new HashMap<>();
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
        callgraph();
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
    private void callgraph() {
        // 调用 opt 工具生成调用图
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "--rm", "-v",
                Paths.get("").toAbsolutePath().toString() + ":/workspace",
                IMAGE, "/bin/bash", "-c",
                "cd /workspace && cd " + PATH_OUTPUT.replace("\\", "/")
                        + " && opt -dot-callgraph " + FILE_LL
                        + " && dot -Tpng " + FILE_DOT + " -o callgraph.png");
        Process process = pb.start();
        if (process.waitFor() != 0) {
            throw new RuntimeException("Failed to generate call graph");
        }
        // 解析生成的调用图
        dotParser(DOTPATH);
    }

    @SneakyThrows
    private void dotParser(Path dotPath) {
        try (BufferedReader reader = Files.newBufferedReader(dotPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.matches("Node\\w+ \\[.*label=.*\\];")) {
                    String nodeId = line.substring(0, line.indexOf(" "));
                    String label = line.replaceAll(".*label=\"\\{(.*?)\\}\".*", "$1");
                    nodeMap.put(label, new NodeInfo(nodeId, -1)); // 初始深度为 -1
                } else if (line.matches("Node\\w+ -> Node\\w+;")) {
                    String[] parts = line.split(" -> ");
                    String fromNode = parts[0].trim();
                    String toNode = parts[1].replace(";", "").trim();
                    callGraph.computeIfAbsent(fromNode, k -> new HashSet<>()).add(toNode);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse dot file", e);
        }

        // 计算所有节点的调用链深度并存储
        for (Map.Entry<String, NodeInfo> entry : nodeMap.entrySet()) {
            String functionName = entry.getKey();
            NodeInfo nodeInfo = entry.getValue();
            int depth = calculateDepth(nodeInfo.getNodeId(), 0, new HashSet<>());
            nodeMap.put(functionName, new NodeInfo(nodeInfo.getNodeId(), depth));
        }

        // 打印调用图和深度信息
        // System.out.println("Node Map: " + nodeMap);
        // System.out.println("Call Graph: " + callGraph);
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
    public int calCallChainDepth(String func) {
        NodeInfo nodeInfo = nodeMap.get(func);
        if (nodeInfo == null) {
            throw new IllegalArgumentException("Function not found: " + func);
        }
        return nodeInfo.getDepth();
    }

}