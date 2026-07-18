package io.github.yanhuo218.autumnwind.inference.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

public final class JdkHostResolver implements HostResolver {

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        return List.copyOf(Arrays.asList(InetAddress.getAllByName(host)));
    }
}
