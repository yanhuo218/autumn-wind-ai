package io.github.yanhuo218.autumnwind.inference.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundTargetPolicyTest {

    private final PublicAddressPolicy addressPolicy = new PublicAddressPolicy();

    @Test
    void 规范化HTTPS目标并保存全部地址的不可变副本() throws UnknownHostException {
        InetAddress ipv4 = address("11.0.0.1");
        InetAddress ipv6 = address("2001:4860::1");
        List<InetAddress> resolved = new ArrayList<>(List.of(ipv4, ipv6));
        AtomicReference<String> resolvedHost = new AtomicReference<>();
        HostResolver resolver = host -> {
            resolvedHost.set(host);
            return resolved;
        };

        ValidatedTarget target = new OutboundTargetPolicy(resolver, addressPolicy)
                .validate(URI.create("HTTPS://Example.COM/a/../v1/chat?mode=test"));
        resolved.clear();

        assertEquals(URI.create("https://example.com:443/v1/chat?mode=test"), target.uri());
        assertEquals("example.com", resolvedHost.get());
        assertEquals(List.of(ipv4, ipv6), target.addresses());
        assertThrows(UnsupportedOperationException.class, () -> target.addresses().add(ipv4));
    }

    @Test
    void 保留合法显式端口() throws UnknownHostException {
        OutboundTargetPolicy policy = policyReturning(address("11.0.0.1"));

        ValidatedTarget target = policy.validate(URI.create("https://model.invalid:8443/v1/chat"));

        assertEquals(URI.create("https://model.invalid:8443/v1/chat"), target.uri());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://model.invalid/v1/chat",
            "https://user@model.invalid/v1/chat",
            "https://model.invalid/v1/chat#fragment",
            "https:///v1/chat",
            "https://model.invalid:0/v1/chat",
            "https://model.invalid:65536/v1/chat",
            "https://model.invalid:invalid/v1/chat"
    })
    void 拒绝非法URI(String value) throws UnknownHostException {
        AtomicInteger resolveCount = new AtomicInteger();
        HostResolver resolver = host -> {
            resolveCount.incrementAndGet();
            return List.of(address("11.0.0.1"));
        };

        assertThrows(TargetPolicyException.class,
                () -> new OutboundTargetPolicy(resolver, addressPolicy).validate(URI.create(value)));
        assertEquals(0, resolveCount.get());
    }

    @Test
    void 混合公网和非公网地址时拒绝整个目标() throws UnknownHostException {
        OutboundTargetPolicy policy = policyReturning(address("11.0.0.1"), address("10.0.0.1"));

        assertThrows(TargetPolicyException.class,
                () -> policy.validate(URI.create("https://model.invalid/v1/chat")));
    }

    @Test
    void 校验并保存同一个DNS地址快照() throws UnknownHostException {
        InetAddress publicAddress = address("11.0.0.1");
        InetAddress privateAddress = address("10.0.0.1");
        AtomicInteger iteratorCount = new AtomicInteger();
        List<InetAddress> changingAddresses = new AbstractList<>() {
            @Override
            public InetAddress get(int index) {
                if (index != 0) {
                    throw new IndexOutOfBoundsException(index);
                }
                return publicAddress;
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public Iterator<InetAddress> iterator() {
                InetAddress current = iteratorCount.getAndIncrement() == 0 ? publicAddress : privateAddress;
                return List.of(current).iterator();
            }
        };
        OutboundTargetPolicy policy = new OutboundTargetPolicy(host -> changingAddresses, addressPolicy);

        ValidatedTarget target = policy.validate(URI.create("https://model.invalid/v1/chat"));

        assertEquals(List.of(publicAddress), target.addresses());
    }

    @Test
    void 拒绝空DNS结果() {
        OutboundTargetPolicy policy = new OutboundTargetPolicy(host -> List.of(), addressPolicy);

        assertThrows(TargetPolicyException.class,
                () -> policy.validate(URI.create("https://model.invalid/v1/chat")));
    }

    @Test
    void DNS失败转换为不泄露目标信息的稳定异常() {
        HostResolver resolver = host -> {
            throw new UnknownHostException("解析结果包含内部诊断信息");
        };
        OutboundTargetPolicy policy = new OutboundTargetPolicy(resolver, addressPolicy);
        String sensitiveTarget = "https://model.invalid/private/path?opaque=REDACTED";

        TargetPolicyException exception = assertThrows(TargetPolicyException.class,
                () -> policy.validate(URI.create(sensitiveTarget)));

        assertEquals("目标主机解析失败。", exception.getMessage());
        assertFalse(exception.getMessage().contains(sensitiveTarget));
        assertFalse(exception.getMessage().contains("内部诊断信息"));
    }

    @Test
    void DNS安全异常转换为不保留底层诊断的稳定异常() {
        HostResolver resolver = host -> {
            throw new SecurityException("底层安全诊断");
        };
        OutboundTargetPolicy policy = new OutboundTargetPolicy(resolver, addressPolicy);

        TargetPolicyException exception = assertThrows(TargetPolicyException.class,
                () -> policy.validate(URI.create("https://model.invalid/private/path")));

        assertEquals("目标主机解析失败。", exception.getMessage());
        assertFalse(exception.getMessage().contains("底层安全诊断"));
        assertNull(exception.getCause());
    }

    @Test
    void 每次校验都重新解析主机的全部地址() throws UnknownHostException {
        AtomicInteger resolveCount = new AtomicInteger();
        HostResolver resolver = host -> {
            resolveCount.incrementAndGet();
            return List.of(address("11.0.0.1"), address("2001:4860::1"));
        };
        OutboundTargetPolicy policy = new OutboundTargetPolicy(resolver, addressPolicy);
        URI uri = URI.create("https://model.invalid/v1/chat");

        assertEquals(2, policy.validate(uri).addresses().size());
        assertEquals(2, policy.validate(uri).addresses().size());
        assertEquals(2, resolveCount.get());
    }

    @Test
    void 拒绝空URI() throws UnknownHostException {
        OutboundTargetPolicy policy = policyReturning(address("11.0.0.1"));

        assertThrows(TargetPolicyException.class, () -> policy.validate(null));
    }

    @Test
    void ValidatedTarget拒绝空地址列表() {
        assertThrows(IllegalArgumentException.class,
                () -> new ValidatedTarget(URI.create("https://model.invalid:443"), List.of()));
    }

    @Test
    void ValidatedTarget拒绝空URI() throws UnknownHostException {
        assertThrows(NullPointerException.class,
                () -> new ValidatedTarget(null, List.of(address("11.0.0.1"))));
    }

    private OutboundTargetPolicy policyReturning(InetAddress... addresses) {
        return new OutboundTargetPolicy(host -> List.of(addresses), addressPolicy);
    }

    private static InetAddress address(String value) throws UnknownHostException {
        return InetAddress.getByName(value);
    }
}
