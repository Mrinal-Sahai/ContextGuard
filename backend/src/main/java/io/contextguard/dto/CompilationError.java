package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompilationError {
    private String file;
    private String language;
    private int line;
    private String message;
    /** ERROR | WARNING */
    private String severity;
}
