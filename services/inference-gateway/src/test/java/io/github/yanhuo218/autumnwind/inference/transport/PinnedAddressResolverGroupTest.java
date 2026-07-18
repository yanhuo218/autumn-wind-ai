package io.github.yanhuo218.autumnwind.inference.transport;

import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PinnedAddressResolverGroupTest {

    private final EventExecutor executor = new DefaultEventExecutor();

    @AfterEach
    void closeExecutor() {
        executor.shutdownGracefully();
    }

    @Test
    void 只返回固定IPv4和IPv6并保留请求端口() throws Exception {
        InetAddress ipv4 = InetAddress.getByAddress(new byte[]{11, 0, 0, 1});
        byte[] ipv6Bytes = new byte[16];
        ipv6Bytes[0] = 0x26;
        ipv6Bytes[1] = 0x06;
        ipv6Bytes[15] = 1;
        InetAddress ipv6 = InetAddress.getByAddress(ipv6Bytes);
        PinnedAddressResolverGroup group = new PinnedAddressResolverGroup(List.of(ipv4, ipv6));
        AddressResolver<InetSocketAddress> resolver = group.getResolver(executor);

        List<InetSocketAddress> resolved = resolver
                .resolveAll(InetSocketAddress.createUnresolved("must-not-use-system-dns.invalid", 8443))
                .syncUninterruptibly()
                .getNow();

        assertEquals(List.of(new InetSocketAddress(ipv4, 8443), new InetSocketAddress(ipv6, 8443)), resolved);
        assertFalse(resolved.get(0).isUnresolved());
        assertFalse(resolved.get(1).isUnresolved());
        group.close();
    }

    @Test
    void 创建时复制地址且结果不可修改() throws Exception {
        InetAddress pinned = InetAddress.getByAddress(new byte[]{11, 0, 0, 2});
        List<InetAddress> source = new ArrayList<>(List.of(pinned));
        PinnedAddressResolverGroup group = new PinnedAddressResolverGroup(source);
        source.clear();
        AddressResolver<InetSocketAddress> resolver = group.getResolver(executor);

        List<InetSocketAddress> resolved = resolver
                .resolveAll(new InetSocketAddress(InetAddress.getByAddress(new byte[]{12, 0, 0, 1}), 443))
                .syncUninterruptibly()
                .getNow();

        assertEquals(List.of(new InetSocketAddress(pinned, 443)), resolved);
        assertThrows(UnsupportedOperationException.class,
                () -> resolved.add(new InetSocketAddress(pinned, 443)));
        group.close();
    }
}
