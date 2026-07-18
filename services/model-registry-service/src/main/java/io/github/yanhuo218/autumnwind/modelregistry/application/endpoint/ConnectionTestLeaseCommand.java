package io.github.yanhuo218.autumnwind.modelregistry.application.endpoint;

import java.util.Objects;
import java.util.UUID;

public record ConnectionTestLeaseCommand(UUID jobId, UUID leaseId, long jobVersion) {

    public ConnectionTestLeaseCommand {
        Objects.requireNonNull(jobId, "连接测试任务标识不能为空。");
        Objects.requireNonNull(leaseId, "租约标识不能为空。");
        if (jobVersion < 0) {
            throw new IllegalArgumentException("任务版本不能为负数。");
        }
    }
}
