package io.github.yanhuo218.autumnwind.inference.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class OutboundTargetPolicy {

    private static final int DEFAULT_HTTPS_PORT = 443;

    private final HostResolver hostResolver;
    private final PublicAddressPolicy addressPolicy;

    public OutboundTargetPolicy(HostResolver hostResolver, PublicAddressPolicy addressPolicy) {
        this.hostResolver = Objects.requireNonNull(hostResolver, "主机解析器不能为空。");
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "地址策略不能为空。");
    }

    public ValidatedTarget validate(URI uri) {
        URI normalizedUri = normalize(uri);
        List<InetAddress> addresses;
        try {
            List<InetAddress> resolvedAddresses = hostResolver.resolve(normalizedUri.getHost());
            addresses = resolvedAddresses == null ? List.of() : List.copyOf(resolvedAddresses);
        } catch (UnknownHostException | SecurityException exception) {
            throw new TargetPolicyException("目标主机解析失败。");
        }
        if (addresses.isEmpty()) {
            throw new TargetPolicyException("目标主机没有可用地址。");
        }
        for (InetAddress address : addresses) {
            addressPolicy.requirePublic(address);
        }
        return new ValidatedTarget(normalizedUri, addresses);
    }

    private static URI normalize(URI uri) {
        if (uri == null
                || !uri.isAbsolute()
                || uri.isOpaque()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
            throw new TargetPolicyException("目标 URI 不合法。");
        }

        int port = uri.getPort() == -1 ? DEFAULT_HTTPS_PORT : uri.getPort();
        if (port < 1 || port > 65535) {
            throw new TargetPolicyException("目标 URI 不合法。");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String authorityHost = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        StringBuilder target = new StringBuilder("https://")
                .append(authorityHost)
                .append(':')
                .append(port);
        if (uri.getRawPath() != null) {
            target.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            target.append('?').append(uri.getRawQuery());
        }
        return URI.create(target.toString()).normalize();
    }
}
