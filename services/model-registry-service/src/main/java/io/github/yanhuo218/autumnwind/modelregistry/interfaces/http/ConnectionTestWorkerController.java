package io.github.yanhuo218.autumnwind.modelregistry.interfaces.http;

import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ConnectionTestFailureCommand;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ConnectionTestJobLeaseView;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ConnectionTestJobVersionView;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ConnectionTestLeaseCommand;
import io.github.yanhuo218.autumnwind.modelregistry.application.endpoint.ConnectionTestWorkerService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping(path = "/internal/v1/model-registry/connection-test-jobs")
public class ConnectionTestWorkerController {

    private final ConnectionTestWorkerService service;

    public ConnectionTestWorkerController(ConnectionTestWorkerService service) {
        this.service = Objects.requireNonNull(service, "连接测试 Worker 服务不能为空。");
    }

    @PostMapping(path = "/claims", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConnectionTestJobLeaseView> claim() {
        return service.claim()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping(path = "/lease-renewals", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ConnectionTestJobVersionView renew(@RequestBody ConnectionTestLeaseCommand command) {
        return service.renew(command);
    }

    @PostMapping(path = "/successes", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ConnectionTestJobVersionView succeed(@RequestBody ConnectionTestLeaseCommand command) {
        return service.succeed(command);
    }

    @PostMapping(path = "/failures", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ConnectionTestJobVersionView fail(@RequestBody ConnectionTestFailureCommand command) {
        return service.fail(command);
    }
}
