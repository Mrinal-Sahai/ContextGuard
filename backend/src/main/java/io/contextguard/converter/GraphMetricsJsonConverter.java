package io.contextguard.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.analysis.flow.CallGraphDiff;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class GraphMetricsJsonConverter
        implements AttributeConverter<CallGraphDiff.GraphMetrics, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(CallGraphDiff.GraphMetrics attribute) {
        try {
            return attribute == null ? null : MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize GraphMetrics", e);
        }
    }

    @Override
    public CallGraphDiff.GraphMetrics convertToEntityAttribute(String dbData) {
        try {
            return dbData == null ? null :
                           MAPPER.readValue(dbData, CallGraphDiff.GraphMetrics.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize GraphMetrics", e);
        }
    }
}

