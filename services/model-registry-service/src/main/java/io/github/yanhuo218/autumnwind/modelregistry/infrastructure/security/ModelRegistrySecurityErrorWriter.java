package io.github.yanhuo218.autumnwind.modelregistry.infrastructure.security;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ModelRegistryErrorCode;
import io.github.yanhuo218.autumnwind.modelregistry.interfaces.http.ApiErrorResponse;
import io.github.yanhuo218.autumnwind.modelregistry.interfaces.http.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

@Component
public class ModelRegistrySecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public ModelRegistrySecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 序列化器不能为空。");
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status,
                      ModelRegistryErrorCode errorCode, String message) throws IOException {
        String correlationId = CorrelationIdFilter.current(request);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                errorCode.value(), message, correlationId, List.of()));
    }
}
