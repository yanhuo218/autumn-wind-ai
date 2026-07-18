package io.github.yanhuo218.autumnwind.conversation.domain.message;

import java.util.Objects;
import java.util.UUID;

public record ContentBlock(ContentBlockType type, String text, UUID resourceId) {

    public ContentBlock {
        type = Objects.requireNonNull(type, "内容块类型不能为空。");
        switch (type) {
            case TEXT -> {
                text = Objects.requireNonNull(text, "文本内容不能为空。").trim();
                if (text.isEmpty()) {
                    throw new IllegalArgumentException("文本内容不能为空白。");
                }
                if (resourceId != null) {
                    throw new IllegalArgumentException("文本内容块不能包含资源引用。");
                }
            }
            case IMAGE_REF, FILE_REF -> {
                if (text != null) {
                    throw new IllegalArgumentException("资源内容块不能包含文本。");
                }
                resourceId = Objects.requireNonNull(resourceId, "资源引用不能为空。");
            }
        }
    }

    public static ContentBlock text(String text) {
        return new ContentBlock(ContentBlockType.TEXT, text, null);
    }

    public static ContentBlock imageReference(UUID resourceId) {
        return new ContentBlock(ContentBlockType.IMAGE_REF, null,
                Objects.requireNonNull(resourceId, "图片引用不能为空。"));
    }

    public static ContentBlock fileReference(UUID resourceId) {
        return new ContentBlock(ContentBlockType.FILE_REF, null,
                Objects.requireNonNull(resourceId, "文件引用不能为空。"));
    }
}
