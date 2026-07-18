package io.github.yanhuo218.autumnwind.inference.security;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

public final class PublicAddressPolicy {

    private static final List<Cidr> FORBIDDEN_IPV4_RANGES = List.of(
            cidr(ipv4(0, 0, 0, 0), 8),
            cidr(ipv4(10, 0, 0, 0), 8),
            cidr(ipv4(100, 64, 0, 0), 10),
            cidr(ipv4(127, 0, 0, 0), 8),
            cidr(ipv4(169, 254, 0, 0), 16),
            cidr(ipv4(172, 16, 0, 0), 12),
            cidr(ipv4(192, 0, 0, 0), 24),
            cidr(ipv4(192, 0, 2, 0), 24),
            cidr(ipv4(192, 168, 0, 0), 16),
            cidr(ipv4(198, 18, 0, 0), 15),
            cidr(ipv4(198, 51, 100, 0), 24),
            cidr(ipv4(203, 0, 113, 0), 24),
            cidr(ipv4(224, 0, 0, 0), 4),
            cidr(ipv4(240, 0, 0, 0), 4)
    );

    private static final List<Cidr> FORBIDDEN_IPV6_RANGES = List.of(
            cidr(ipv6(), 128),
            cidr(ipv6(0, 0, 0, 0, 0, 0, 0, 1), 128),
            cidr(ipv6(0xfc00), 7),
            cidr(ipv6(0xfe80), 10),
            cidr(ipv6(0xff00), 8),
            cidr(ipv6(0x2001, 0x0db8), 32)
    );

    public void requirePublic(InetAddress address) {
        if (address == null) {
            throw new TargetPolicyException("目标地址不是公网地址。");
        }

        byte[] bytes = address.getAddress();
        if (isIpv4MappedIpv6(bytes)) {
            requireAllowed(bytes, 12, FORBIDDEN_IPV4_RANGES);
            return;
        }

        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            throw new TargetPolicyException("目标地址不是公网地址。");
        }

        if (bytes.length == 4) {
            requireAllowed(bytes, 0, FORBIDDEN_IPV4_RANGES);
            return;
        }
        if (bytes.length == 16) {
            requireAllowed(bytes, 0, FORBIDDEN_IPV6_RANGES);
            return;
        }
        throw new TargetPolicyException("目标地址不是公网地址。");
    }

    private static void requireAllowed(byte[] address, int offset, List<Cidr> forbiddenRanges) {
        byte[] comparableAddress = Arrays.copyOfRange(address, offset, address.length);
        if (forbiddenRanges.stream().anyMatch(range -> range.matches(comparableAddress))) {
            throw new TargetPolicyException("目标地址不是公网地址。");
        }
    }

    private static boolean isIpv4MappedIpv6(byte[] bytes) {
        if (bytes.length != 16 || bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static Cidr cidr(byte[] network, int prefixLength) {
        return new Cidr(network, prefixLength);
    }

    private static byte[] ipv4(int first, int second, int third, int fourth) {
        return new byte[]{(byte) first, (byte) second, (byte) third, (byte) fourth};
    }

    private static byte[] ipv6(int... groups) {
        byte[] bytes = new byte[16];
        for (int index = 0; index < groups.length; index++) {
            bytes[index * 2] = (byte) (groups[index] >>> 8);
            bytes[index * 2 + 1] = (byte) groups[index];
        }
        return bytes;
    }

    private record Cidr(byte[] network, int prefixLength) {

        private boolean matches(byte[] address) {
            if (network.length != address.length) {
                return false;
            }
            int fullBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int index = 0; index < fullBytes; index++) {
                if (network[index] != address[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (Byte.SIZE - remainingBits);
            return (network[fullBytes] & mask) == (address[fullBytes] & mask);
        }
    }
}
