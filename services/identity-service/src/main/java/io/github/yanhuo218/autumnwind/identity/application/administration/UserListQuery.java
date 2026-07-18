package io.github.yanhuo218.autumnwind.identity.application.administration;

import io.github.yanhuo218.autumnwind.identity.domain.account.AccountStatus;

public record UserListQuery(String query, AccountStatus status, int page, int size) {

    public UserListQuery {
        query = query == null ? "" : query.strip();
        if (query.codePointCount(0, query.length()) > 128) {
            throw new IllegalArgumentException("用户搜索条件过长。");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("用户分页参数不合法。");
        }
    }
}
