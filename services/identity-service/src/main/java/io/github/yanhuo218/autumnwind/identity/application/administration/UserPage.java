package io.github.yanhuo218.autumnwind.identity.application.administration;

import java.util.List;

public record UserPage(
        List<UserAdminView> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
