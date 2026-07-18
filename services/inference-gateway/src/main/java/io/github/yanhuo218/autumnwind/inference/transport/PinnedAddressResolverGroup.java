package io.github.yanhuo218.autumnwind.inference.transport;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

public final class PinnedAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final List<InetAddress> addresses;

    public PinnedAddressResolverGroup(List<InetAddress> addresses) {
        this.addresses = List.copyOf(Objects.requireNonNull(addresses, "固定地址不能为空。"));
        if (this.addresses.isEmpty()) {
            throw new IllegalArgumentException("固定地址不能为空。");
        }
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
            @Override
            protected boolean doIsResolved(InetSocketAddress address) {
                return false;
            }

            @Override
            protected void doResolve(
                    InetSocketAddress unresolvedAddress,
                    Promise<InetSocketAddress> promise
            ) {
                promise.setSuccess(new InetSocketAddress(addresses.getFirst(), unresolvedAddress.getPort()));
            }

            @Override
            protected void doResolveAll(
                    InetSocketAddress unresolvedAddress,
                    Promise<List<InetSocketAddress>> promise
            ) {
                List<InetSocketAddress> resolved = addresses.stream()
                        .map(address -> new InetSocketAddress(address, unresolvedAddress.getPort()))
                        .toList();
                promise.setSuccess(resolved);
            }
        };
    }
}
