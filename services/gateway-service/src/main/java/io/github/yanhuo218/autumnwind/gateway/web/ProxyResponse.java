package io.github.yanhuo218.autumnwind.gateway.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

import java.util.Objects;

public record ProxyResponse(HttpStatusCode status, HttpHeaders headers, byte[] body) {

    public ProxyResponse {
        status = Objects.requireNonNull(status, "下游响应状态不能为空。");
        headers = Objects.requireNonNull(headers, "下游响应头不能为空。");
        body = Objects.requireNonNull(body, "下游响应正文不能为空。");
    }

    @Override
    public String toString() {
        return "ProxyResponse[status=" + status.value()
                + ", headerNames=" + headers.headerNames()
                + ", bodyLength=" + body.length + "]";
    }
}
