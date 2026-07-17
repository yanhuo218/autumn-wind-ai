package io.github.yanhuo218.autumnwind.notification.domain.email;

public enum EmailJobStatus {
    QUEUED,
    SENDING,
    SUCCEEDED,
    RETRY_SCHEDULED,
    FAILED
}
