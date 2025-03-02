package io.github.astatine202.scras.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class SliceResult {
    private String variable;
    private String function;
    private List<Integer> lines;
}