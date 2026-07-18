package io.github.yanhuo218.autumnwind.inference.security;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;

public record ValidatedTarget(URI uri, List<InetAddress> addresses) {

    public ValidatedTarget {
        Objects.requireNonNull(uri, "目标 URI 不能为空。");
        addresses = List.copyOf(addresses);
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("目标地址不能为空。");
        }
    }
}
