package io.github.yanhuo218.autumnwind.notification.interfaces.http;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.github.yanhuo218.autumnwind.notification.application.smtp.SmtpConfigUpdateCommand;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSecurityMode;
import io.github.yanhuo218.autumnwind.notification.domain.smtp.SmtpSettings;

import java.util.UUID;

public final class SmtpConfigUpdateRequest {

    private String host;
    private Integer port;
    private SmtpSecurityMode securityMode;
    private String username;
    private String password;
    private Boolean clearPassword;
    private String fromAddress;
    private String fromName;
    private Long expectedVersion;

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public void setSecurityMode(SmtpSecurityMode securityMode) {
        this.securityMode = securityMode;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setPassword(String password) {
        this.password = password;
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setClearPassword(Boolean clearPassword) {
        this.clearPassword = clearPassword;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public void setExpectedVersion(Long expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public SmtpConfigUpdateCommand toCommand(UUID actorUserId) {
        return new SmtpConfigUpdateCommand(
                new SmtpSettings(
                        host,
                        requirePresent(port, "SMTP 端口不能为空。"),
                        securityMode,
                        username,
                        fromAddress,
                        fromName
                ),
                password,
                Boolean.TRUE.equals(clearPassword),
                requirePresent(expectedVersion, "SMTP 配置预期版本不能为空。"),
                actorUserId
        );
    }

    private static <T> T requirePresent(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    @Override
    public String toString() {
        return "SmtpConfigUpdateRequest[host=" + host + ", port=" + port
                + ", securityMode=" + securityMode + ", username=" + username
                + ", password=<REDACTED>, clearPassword=" + clearPassword
                + ", fromAddress=" + fromAddress + ", fromName=" + fromName
                + ", expectedVersion=" + expectedVersion + "]";
    }
}
