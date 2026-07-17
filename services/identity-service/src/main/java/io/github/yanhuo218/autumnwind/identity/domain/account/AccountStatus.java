package io.github.yanhuo218.autumnwind.identity.domain.account;

import java.util.Set;

public enum AccountStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    DISABLED,
    DELETION_PENDING,
    DELETED;

    public boolean canLogin() {
        return this == ACTIVE;
    }

    public boolean canTransitionTo(AccountStatus target) {
        return switch (this) {
            case PENDING_VERIFICATION -> Set.of(ACTIVE, DISABLED, DELETION_PENDING).contains(target);
            case ACTIVE -> Set.of(DISABLED, DELETION_PENDING).contains(target);
            case DISABLED -> Set.of(ACTIVE, DELETION_PENDING).contains(target);
            case DELETION_PENDING -> target == DELETED;
            case DELETED -> false;
        };
    }
}
