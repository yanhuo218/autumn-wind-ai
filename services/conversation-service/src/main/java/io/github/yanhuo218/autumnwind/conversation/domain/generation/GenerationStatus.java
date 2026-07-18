package io.github.yanhuo218.autumnwind.conversation.domain.generation;

import java.util.Objects;

public enum GenerationStatus {
    PENDING,
    STREAMING,
    SUCCEEDED,
    FAILED,
    STOPPED,
    INTERRUPTED;

    public boolean canTransitionTo(GenerationStatus target) {
        return switch (this) {
            case PENDING -> target == STREAMING || target == FAILED || target == STOPPED || target == INTERRUPTED;
            case STREAMING -> target == SUCCEEDED || target == FAILED || target == STOPPED || target == INTERRUPTED;
            case SUCCEEDED, FAILED, STOPPED, INTERRUPTED -> false;
        };
    }

    public void requireTransitionTo(GenerationStatus target) {
        Objects.requireNonNull(target, "目标生成状态不能为空。");
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("不允许从 " + this + " 转换到 " + target + "。");
        }
    }

    public boolean terminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, STOPPED, INTERRUPTED -> true;
            case PENDING, STREAMING -> false;
        };
    }
}
