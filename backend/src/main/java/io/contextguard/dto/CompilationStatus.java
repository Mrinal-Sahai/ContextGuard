package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompilationStatus {
    private boolean hasErrors;
    private int errorCount;
    private int warningCount;
    /** Languages that were AST-parsed in this analysis */
    private List<String> parsedLanguages;
    private List<CompilationError> errors;
}
