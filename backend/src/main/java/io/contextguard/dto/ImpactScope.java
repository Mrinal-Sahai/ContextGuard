package io.contextguard.dto;


public enum ImpactScope {
    LOCALIZED,      // Single file/directory
    COMPONENT,      // Single component (e.g., service layer)
    MODULE,         // Single module (e.g., auth module)
    SYSTEM_WIDE     // Multiple modules/system-wide changes
}
