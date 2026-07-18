package io.github.yanhuo218.autumnwind.conversation.domain.message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageContentTest {

    @Test
    void 创建带版本的不可变混合内容() {
        UUID imageId = UUID.randomUUID();
        List<ContentBlock> source = new ArrayList<>(List.of(
                ContentBlock.text(" 你好 "),
                ContentBlock.imageReference(imageId)
        ));

        MessageContent content = new MessageContent(1, source);
        source.clear();

        assertEquals("你好", content.blocks().getFirst().text());
        assertEquals(imageId, content.blocks().get(1).resourceId());
        assertThrows(UnsupportedOperationException.class,
                () -> content.blocks().add(ContentBlock.text("其他")));
    }

    @Test
    void 拒绝非法内容块和版本() {
        assertThrows(IllegalArgumentException.class, () -> ContentBlock.text("  "));
        assertThrows(NullPointerException.class, () -> ContentBlock.fileReference(null));
        assertThrows(IllegalArgumentException.class,
                () -> new MessageContent(2, List.of(ContentBlock.text("文本"))));
        assertThrows(IllegalArgumentException.class, () -> new MessageContent(1, List.of()));
    }

    @Test
    void 公共构造器拒绝字段冲突和缺失引用() {
        UUID resourceId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> new ContentBlock(ContentBlockType.TEXT, "文本", resourceId));
        assertThrows(IllegalArgumentException.class,
                () -> new ContentBlock(ContentBlockType.IMAGE_REF, "文本", resourceId));
        assertThrows(NullPointerException.class,
                () -> new ContentBlock(ContentBlockType.FILE_REF, null, null));
    }
}
