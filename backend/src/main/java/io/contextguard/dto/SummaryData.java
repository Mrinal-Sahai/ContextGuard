package io.contextguard.dto;

import lombok.Data;
import java.util.List;

@Data
public class SummaryData {
    private String summary;
    private String why;
    private List<String> risks;
    private List<String> reviewChecklist;
}
