package io.github.yanhuo218.autumnwind.inference.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicAddressPolicyTest {

    private final PublicAddressPolicy policy = new PublicAddressPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"11.0.0.1", "2001:4860::1"})
    void 接受公网IPv4和IPv6(String value) throws UnknownHostException {
        assertDoesNotThrow(() -> policy.requirePublic(address(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "0.255.255.255",
            "10.0.0.0", "10.255.255.255",
            "100.64.0.0", "100.127.255.255",
            "127.0.0.0", "127.255.255.255",
            "169.254.0.0", "169.254.255.255",
            "172.16.0.0", "172.31.255.255",
            "192.0.0.0", "192.0.0.255",
            "192.0.2.0", "192.0.2.255",
            "192.168.0.0", "192.168.255.255",
            "198.18.0.0", "198.19.255.255",
            "198.51.100.0", "198.51.100.255",
            "203.0.113.0", "203.0.113.255",
            "224.0.0.0", "239.255.255.255",
            "240.0.0.0", "255.255.255.255"
    })
    void 拒绝IPv4禁止范围的边界值(String value) throws UnknownHostException {
        assertThrows(TargetPolicyException.class, () -> policy.requirePublic(address(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "::", "::1",
            "fc00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "fe80::", "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "ff00::", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "2001:db8::", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff"
    })
    void 拒绝IPv6禁止范围的边界值(String value) throws UnknownHostException {
        assertThrows(TargetPolicyException.class, () -> policy.requirePublic(address(value)));
    }

    @Test
    void IPv4映射IPv6按嵌入地址判断() throws UnknownHostException {
        assertThrows(TargetPolicyException.class,
                () -> policy.requirePublic(mappedAddress(192, 168, 1, 1)));
        assertDoesNotThrow(() -> policy.requirePublic(mappedAddress(11, 0, 0, 1)));
    }

    @Test
    void 拒绝空地址() {
        assertThrows(TargetPolicyException.class, () -> policy.requirePublic(null));
    }

    private static InetAddress address(String value) throws UnknownHostException {
        return InetAddress.getByName(value);
    }

    private static Inet6Address mappedAddress(int first, int second, int third, int fourth)
            throws UnknownHostException {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        bytes[12] = (byte) first;
        bytes[13] = (byte) second;
        bytes[14] = (byte) third;
        bytes[15] = (byte) fourth;
        return Inet6Address.getByAddress(null, bytes, -1);
    }
}
