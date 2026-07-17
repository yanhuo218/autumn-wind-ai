package io.github.yanhuo218.autumnwind.notification.domain.smtp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmtpSettingsTest {

    @Test
    void 规范化合法设置() {
        SmtpSettings settings = new SmtpSettings(
                " SMTP.Example.COM. ",
                587,
                SmtpSecurityMode.STARTTLS,
                " ",
                " sender@example.com ",
                " Autumn Wind Ai "
        );

        assertEquals("smtp.example.com", settings.host());
        assertNull(settings.username());
        assertEquals("sender@example.com", settings.fromAddress());
        assertEquals("Autumn Wind Ai", settings.fromName());
    }

    @Test
    void 拒绝非法端口和Host() {
        assertThrows(IllegalArgumentException.class, () -> settings("smtp example.com", 587));
        assertThrows(IllegalArgumentException.class, () -> settings("smtp.example.com/path", 587));
        assertThrows(IllegalArgumentException.class, () -> settings("smtp.example.com:587", 587));
        assertThrows(IllegalArgumentException.class, () -> settings("-smtp.example.com", 587));
        assertThrows(IllegalArgumentException.class, () -> settings("256.1.1.1", 587));
        assertThrows(IllegalArgumentException.class, () -> settings("smtp.example.com", 0));
        assertThrows(IllegalArgumentException.class, () -> settings("smtp.example.com", 65536));
    }

    @Test
    void 拒绝非法发件地址和控制字符() {
        assertThrows(IllegalArgumentException.class, () -> new SmtpSettings(
                "smtp.example.com",
                465,
                SmtpSecurityMode.TLS,
                "user",
                "invalid-address",
                "Autumn Wind Ai"
        ));
        assertThrows(IllegalArgumentException.class, () -> new SmtpSettings(
                "smtp.example.com",
                465,
                SmtpSecurityMode.TLS,
                "user",
                "sender@example.com",
                "Bad\r\nName"
        ));
        assertThrows(IllegalArgumentException.class, () -> settingsWithAddress("sender @example.com"));
        assertThrows(IllegalArgumentException.class, () -> settingsWithAddress("a@b..com"));
        assertThrows(IllegalArgumentException.class, () -> settingsWithAddress(".sender@example.com"));
    }

    private static SmtpSettings settings(String host, int port) {
        return new SmtpSettings(
                host,
                port,
                SmtpSecurityMode.STARTTLS,
                "user",
                "sender@example.com",
                "Autumn Wind Ai"
        );
    }

    private static SmtpSettings settingsWithAddress(String fromAddress) {
        return new SmtpSettings(
                "smtp.example.com",
                587,
                SmtpSecurityMode.STARTTLS,
                "user",
                fromAddress,
                "Autumn Wind Ai"
        );
    }
}
