package io.contextguard.engine;

import java.util.List;

/**
 * Represents a single unified-diff hunk with parsed ranges and lines.
 */
public class DiffHunk {
    private final int oldStart;
    private final int oldCount;
    private final int newStart;
    private final int newCount;
    private final List<String> lines; // lines of the hunk (with +, - or ' ' prefixes)

    public DiffHunk(int oldStart, int oldCount, int newStart, int newCount, List<String> lines) {
        this.oldStart = oldStart;
        this.oldCount = oldCount;
        this.newStart = newStart;
        this.newCount = newCount;
        this.lines = lines;
    }

    public int getOldStart() { return oldStart; }
    public int getOldCount() { return oldCount; }
    public int getNewStart() { return newStart; }
    public int getNewCount() { return newCount; }
    public List<String> getLines() { return lines; }
}

