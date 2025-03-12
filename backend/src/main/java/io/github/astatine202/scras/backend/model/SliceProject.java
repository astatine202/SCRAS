package io.github.astatine202.scras.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
public class SliceProject {
    private String variable;
    private String function;
    private String fileName;
    private Map<String, List<Integer>> lines;
}
