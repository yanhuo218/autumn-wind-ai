package io.github.yanhuo218.autumnwind.inference.chat;

import tools.jackson.databind.ObjectMapper;
import io.github.yanhuo218.autumnwind.inference.transport.ProviderFrame;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiStreamDecoderTest {

    private final OpenAiStreamDecoder decoder = new OpenAiStreamDecoder(new ObjectMapper());

    @Test
    void 解码LF与CRLF跨帧多事件和UTF8内容() {
        byte[] payload = ("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先思考\"}}],\"unknown\":true}\r\n\r\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"秋风\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning\":\"再检查\",\"content\":\"起\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10},\"ignored\":{}}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        int chineseBoundary = indexOf(payload, "秋".getBytes(StandardCharsets.UTF_8)) + 1;

        List<InferenceEvent> events = decoder.decode(Flux.just(
                        frame(Arrays.copyOfRange(payload, 0, 17)),
                        frame(Arrays.copyOfRange(payload, 17, chineseBoundary)),
                        frame(Arrays.copyOfRange(payload, chineseBoundary, payload.length - 6)),
                        frame(Arrays.copyOfRange(payload, payload.length - 6, payload.length))), true)
                .collectList()
                .block();

        assertEquals(6, events.size());
        assertEquals("先思考", assertInstanceOf(InferenceEvent.Reasoning.class, events.get(0)).delta());
        assertEquals("秋风", assertInstanceOf(InferenceEvent.TextDelta.class, events.get(1)).delta());
        assertEquals("再检查", assertInstanceOf(InferenceEvent.Reasoning.class, events.get(2)).delta());
        assertEquals("起", assertInstanceOf(InferenceEvent.TextDelta.class, events.get(3)).delta());
        InferenceEvent.Usage usage = assertInstanceOf(InferenceEvent.Usage.class, events.get(4));
        assertEquals(7, usage.promptTokens());
        assertEquals(3, usage.completionTokens());
        assertEquals(10, usage.totalTokens());
        assertEquals("stop", assertInstanceOf(InferenceEvent.Done.class, events.get(5)).finishReason());
        assertEquals("done", events.get(5).type());
        assertEquals(1, events.stream().filter(InferenceEvent.Done.class::isInstance).count());
    }

    @Test
    void DONE只产生一次并忽略其后数据() {
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"before\"},\"finish_reason\":null}]}\n\n"
                + "data: [DONE]\n\n"
                + "data: {not-json-after-done}\n\n"
                + "data: [DONE]\n\n";

        List<InferenceEvent> events = decoder.decode(Flux.just(frame(stream)), true).collectList().block();

        assertEquals(2, events.size());
        assertEquals("before", assertInstanceOf(InferenceEvent.TextDelta.class, events.get(0)).delta());
        assertNull(assertInstanceOf(InferenceEvent.Done.class, events.get(1)).finishReason());
    }

    @Test
    void 未声明reasoning能力时忽略增量且空增量不产生事件() {
        String stream = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hidden\",\"content\":\"\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"visible\"}}]}\n\n"
                + "data: [DONE]\n\n";

        List<InferenceEvent> events = decoder.decode(Flux.just(frame(stream)), false).collectList().block();

        assertEquals(2, events.size());
        assertEquals("visible", assertInstanceOf(InferenceEvent.TextDelta.class, events.get(0)).delta());
        assertInstanceOf(InferenceEvent.Done.class, events.get(1));
    }

    @Test
    void 流式响应保留纯空格reasoning和文本增量() {
        String stream = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\" \",\"content\":\" \"}}]}\n\n"
                + "data: [DONE]\n\n";

        List<InferenceEvent> events = decoder.decode(Flux.just(frame(stream)), true).collectList().block();

        assertEquals(3, events.size());
        assertEquals(" ", assertInstanceOf(InferenceEvent.Reasoning.class, events.get(0)).delta());
        assertEquals(" ", assertInstanceOf(InferenceEvent.TextDelta.class, events.get(1)).delta());
        assertInstanceOf(InferenceEvent.Done.class, events.get(2));
    }

    @Test
    void usage缺少的值保持为空() {
        String stream = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4}}\n\n"
                + "data: [DONE]\n\n";

        List<InferenceEvent> events = decoder.decode(Flux.just(frame(stream)), false).collectList().block();

        InferenceEvent.Usage usage = assertInstanceOf(InferenceEvent.Usage.class, events.get(0));
        assertEquals(4, usage.promptTokens());
        assertNull(usage.completionTokens());
        assertNull(usage.totalTokens());
    }

    @Test
    void 畸形JSON转换为不包含原文的安全异常() {
        String sensitive = "sensitive-provider-payload-placeholder";

        ProviderResponseException exception = assertThrows(ProviderResponseException.class,
                () -> decoder.decode(Flux.just(frame("data: {\"choices\":[" + sensitive + "}\n\n")), true)
                        .collectList().block());

        assertEquals("服务商响应无效。", exception.getMessage());
        assertTrue(exception.getCause() == null);
        assertTrue(exception.toString().contains(sensitive) == false);
    }

    @Test
    void 非法UTF8转换为安全异常() {
        byte[] prefix = "data: ".getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = "\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        bytes[prefix.length] = (byte) 0xc3;
        bytes[prefix.length + 1] = (byte) 0x28;
        System.arraycopy(suffix, 0, bytes, prefix.length + 2, suffix.length);

        assertThrows(ProviderResponseException.class,
                () -> decoder.decode(Flux.just(frame(bytes)), true).collectList().block());
    }

    @Test
    void 流结束缺少DONE时失败() {
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n";

        assertThrows(ProviderResponseException.class,
                () -> decoder.decode(Flux.just(frame(stream)), true).collectList().block());
    }

    @Test
    void 不合法choices结构失败() {
        String stream = "data: {\"choices\":{\"delta\":{}}}\n\ndata: [DONE]\n\n";

        assertThrows(ProviderResponseException.class,
                () -> decoder.decode(Flux.just(frame(stream)), true).collectList().block());
    }

    @Test
    void 解码合法非流响应并保持事件顺序() {
        String response = "{\"choices\":[{\"message\":{\"reasoning_content\":\"分析\",\"content\":\"回答\"},"
                + "\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":5,\"total_tokens\":7},"
                + "\"unknown\":true}";

        List<InferenceEvent> events = decoder.decodeNonStreaming(Flux.just(
                        frame(response.substring(0, 25)), frame(response.substring(25))), true)
                .collectList().block();

        assertEquals(4, events.size());
        assertEquals("分析", assertInstanceOf(InferenceEvent.Reasoning.class, events.get(0)).delta());
        assertEquals("回答", assertInstanceOf(InferenceEvent.TextDelta.class, events.get(1)).delta());
        assertInstanceOf(InferenceEvent.Usage.class, events.get(2));
        assertEquals("length", assertInstanceOf(InferenceEvent.Done.class, events.get(3)).finishReason());
    }

    @Test
    void 非流响应兼容reasoning字段() {
        String response = "{\"choices\":[{\"message\":{\"reasoning\":\"检查\",\"content\":\"ok\"},\"finish_reason\":null}]}";

        List<InferenceEvent> events = decoder.decodeNonStreaming(Flux.just(frame(response)), true)
                .collectList().block();

        assertEquals("检查", assertInstanceOf(InferenceEvent.Reasoning.class, events.get(0)).delta());
    }

    @Test
    void 非流响应保留纯空格reasoning和文本增量() {
        String response = "{\"choices\":[{\"message\":{\"reasoning_content\":\" \",\"content\":\" \"},"
                + "\"finish_reason\":null}]}";

        List<InferenceEvent> events = decoder.decodeNonStreaming(Flux.just(frame(response)), true)
                .collectList().block();

        assertEquals(3, events.size());
        assertEquals(" ", assertInstanceOf(InferenceEvent.Reasoning.class, events.get(0)).delta());
        assertEquals(" ", assertInstanceOf(InferenceEvent.TextDelta.class, events.get(1)).delta());
        assertInstanceOf(InferenceEvent.Done.class, events.get(2));
    }

    @Test
    void 非流空响应畸形响应和缺少message均失败() {
        assertThrows(ProviderResponseException.class,
                () -> decoder.decodeNonStreaming(Flux.just(frame(new byte[0])), true).collectList().block());
        assertThrows(ProviderResponseException.class,
                () -> decoder.decodeNonStreaming(Flux.just(frame("not-json")), true).collectList().block());
        assertThrows(ProviderResponseException.class,
                () -> decoder.decodeNonStreaming(Flux.just(frame("{\"choices\":[]}")), true).collectList().block());
        assertThrows(ProviderResponseException.class,
                () -> decoder.decodeNonStreaming(Flux.empty(), true).collectList().block());
    }

    private static ProviderFrame frame(String data) {
        return frame(data.getBytes(StandardCharsets.UTF_8));
    }

    private static ProviderFrame frame(byte[] data) {
        return new ProviderFrame(200, data);
    }

    private static int indexOf(byte[] source, byte[] pattern) {
        for (int start = 0; start <= source.length - pattern.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < pattern.length; offset++) {
                if (source[start + offset] != pattern[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return start;
            }
        }
        throw new IllegalArgumentException("测试数据缺少预期 UTF-8 字节。");
    }
}
