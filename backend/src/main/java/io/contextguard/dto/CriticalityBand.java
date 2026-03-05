package io.contextguard.dto;

public enum CriticalityBand {
    LOW,       // 0–2: routine change
    NOTABLE,   // 3–5: one significant signal
    CRITICAL,  // 6–8: multiple signals, deep-dive required
    SEVERE     // 9+:  combination of impact + risk, consider blocking
}
