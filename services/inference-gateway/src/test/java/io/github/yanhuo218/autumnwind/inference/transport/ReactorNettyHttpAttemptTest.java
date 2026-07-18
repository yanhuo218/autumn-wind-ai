package io.github.yanhuo218.autumnwind.inference.transport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.github.yanhuo218.autumnwind.inference.security.OutboundTargetPolicy;
import io.github.yanhuo218.autumnwind.inference.security.PublicAddressPolicy;
import io.github.yanhuo218.autumnwind.inference.security.ValidatedTarget;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReactorNettyHttpAttemptTest {

    private static final String PROVIDER_HOST = "provider.invalid";
    private static final String AUTHORIZATION_PLACEHOLDER = "Bearer PLACEHOLDER_API_KEY_BYTES";

    private static final AtomicInteger CONNECTION_COUNT = new AtomicInteger();
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static final AtomicInteger FOLLOWED_COUNT = new AtomicInteger();
    private static final List<String> AUTHORIZATIONS = new CopyOnWriteArrayList<>();
    private static final List<String> SNI_HOSTS = new CopyOnWriteArrayList<>();

    @TempDir
    static Path temporaryDirectory;

    private static DisposableServer server;
    private static ReactorNettyProviderExchangeClient client;
    private static InetAddress loopback;

    @BeforeAll
    static void 启动本地TLS服务() throws Exception {
        loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        TlsContexts contexts = createTlsContexts();
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .secure(spec -> spec.sslContext(contexts.server()))
                .doOnConnection(connection -> CONNECTION_COUNT.incrementAndGet())
                .handle((request, response) -> {
                    REQUEST_COUNT.incrementAndGet();
                    AUTHORIZATIONS.add(request.requestHeaders().get(HttpHeaderNames.AUTHORIZATION));
                    request.withConnection(connection -> captureSni(connection.channel().pipeline().get(SslHandler.class)));
                    if ("/redirect".equals(request.uri())) {
                        return response.status(302)
                                .header(HttpHeaderNames.LOCATION,
                                        "https://" + PROVIDER_HOST + ":" + server.port() + "/followed")
                                .sendString(Mono.just("redirect-placeholder"));
                    }
                    if ("/followed".equals(request.uri())) {
                        FOLLOWED_COUNT.incrementAndGet();
                        return response.sendString(Mono.just("followed-placeholder"));
                    }
                    if ("/disconnect".equals(request.uri())) {
                        request.withConnection(connection -> connection.dispose());
                        return Mono.never();
                    }
                    return response.sendString(Mono.just("response-placeholder"));
                })
                .bindNow(Duration.ofSeconds(5));

        OutboundTargetPolicy redirectPolicy = new OutboundTargetPolicy(
                host -> List.of(InetAddress.getByAddress(new byte[]{11, 0, 0, 1})),
                new PublicAddressPolicy());
        client = new ReactorNettyProviderExchangeClient(
                redirectPolicy,
                new ReactorNettyProviderExchangeClient.ReactorNettyHttpAttempt(contexts.client()));
    }

    @AfterAll
    static void 停止本地TLS服务() {
        if (server != null) {
            server.disposeNow(Duration.ofSeconds(5));
        }
    }

    @Test
    void 固定地址连接不可解析Host并使用原始SNI和Authorization() {
        int connectionsBefore = CONNECTION_COUNT.get();
        int requestsBefore = REQUEST_COUNT.get();

        List<ProviderFrame> frames = exchange("/ok").collectList().block(Duration.ofSeconds(5));

        assertEquals("response-placeholder", body(frames));
        assertEquals(connectionsBefore + 1, CONNECTION_COUNT.get());
        assertEquals(requestsBefore + 1, REQUEST_COUNT.get());
        assertEquals(PROVIDER_HOST, SNI_HOSTS.getLast());
        assertEquals(AUTHORIZATION_PLACEHOLDER, AUTHORIZATIONS.getLast());
    }

    @Test
    void 每次Exchange使用新连接() {
        int connectionsBefore = CONNECTION_COUNT.get();

        exchange("/first").blockLast(Duration.ofSeconds(5));
        exchange("/second").blockLast(Duration.ofSeconds(5));

        assertEquals(connectionsBefore + 2, CONNECTION_COUNT.get());
    }

    @Test
    void 不自动跟随302() {
        int requestsBefore = REQUEST_COUNT.get();
        int followedBefore = FOLLOWED_COUNT.get();

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> exchange("/redirect").blockLast(Duration.ofSeconds(5)));

        assertEquals("服务商重定向被拒绝。", error.getMessage());
        assertEquals(requestsBefore + 1, REQUEST_COUNT.get());
        assertEquals(followedBefore, FOLLOWED_COUNT.get());
    }

    @Test
    void 连接断开不会自动重试() {
        int requestsBefore = REQUEST_COUNT.get();
        Logger logger = (Logger) LoggerFactory.getLogger("reactor.netty.http.client.HttpClientConnect");
        Level originalLevel = logger.getLevel();

        RuntimeException error;
        try {
            logger.setLevel(Level.ERROR);
            error = assertThrows(RuntimeException.class,
                    () -> exchange("/disconnect").blockLast(Duration.ofSeconds(5)));
        } finally {
            logger.setLevel(originalLevel);
        }

        assertSame(originalLevel, logger.getLevel());
        assertEquals("服务商请求失败。", error.getMessage());
        assertEquals(requestsBefore + 1, REQUEST_COUNT.get());
    }

    private static Flux<ProviderFrame> exchange(String path) {
        URI uri = URI.create("https://" + PROVIDER_HOST + ":" + server.port() + path);
        ValidatedTarget target = new ValidatedTarget(uri, List.of(loopback));
        ProviderRequest request = new ProviderRequest(
                "PLACEHOLDER_API_KEY_BYTES".getBytes(StandardCharsets.US_ASCII),
                "request-placeholder".getBytes(StandardCharsets.US_ASCII));
        return client.exchange(target, request);
    }

    private static String body(List<ProviderFrame> frames) {
        int length = frames.stream().mapToInt(frame -> frame.data().length).sum();
        byte[] body = new byte[length];
        int offset = 0;
        for (ProviderFrame frame : frames) {
            byte[] data = frame.data();
            System.arraycopy(data, 0, body, offset, data.length);
            offset += data.length;
        }
        return new String(body, StandardCharsets.US_ASCII);
    }

    private static void captureSni(SslHandler handler) {
        ExtendedSSLSession session = (ExtendedSSLSession) handler.engine().getSession();
        session.getRequestedServerNames().stream()
                .filter(SNIHostName.class::isInstance)
                .map(SNIHostName.class::cast)
                .map(SNIHostName::getAsciiName)
                .findFirst()
                .ifPresent(SNI_HOSTS::add);
    }

    private static TlsContexts createTlsContexts() throws Exception {
        Path keyStorePath = temporaryDirectory.resolve("provider-invalid-placeholder.p12");
        String password = UUID.randomUUID().toString();
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias", "provider-invalid-placeholder",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=" + PROVIDER_HOST,
                "-ext", "SAN=dns:" + PROVIDER_HOST,
                "-storetype", "PKCS12",
                "-keystore", keyStorePath.toString(),
                "-storepass", password,
                "-keypass", password,
                "-noprompt"
        ).redirectErrorStream(true).start();
        try (InputStream output = process.getInputStream()) {
            output.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (process.waitFor() != 0) {
            throw new IllegalStateException("测试 TLS 证书生成失败。");
        }

        char[] passwordCharacters = password.toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(keyStorePath)) {
                keyStore.load(input, passwordCharacters);
            }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, passwordCharacters);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(keyStore);
            return new TlsContexts(
                    SslContextBuilder.forServer(keyManagers).build(),
                    SslContextBuilder.forClient().trustManager(trustManagers).build());
        } finally {
            Arrays.fill(passwordCharacters, '\0');
        }
    }

    private record TlsContexts(SslContext server, SslContext client) {
    }
}
