package io.github.yanhuo218.autumnwind.conversation.domain.message;

import java.util.List;
import java.util.Objects;

public record MessageContent(int schemaVersion, List<ContentBlock> blocks) {

    public MessageContent {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("不支持的消息内容版本。");
        }
        blocks = List.copyOf(Objects.requireNonNull(blocks, "内容块集合不能为空。"));
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("内容块集合不能为空。");
        }
    }
}
