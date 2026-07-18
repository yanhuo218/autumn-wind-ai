package io.github.yanhuo218.autumnwind.conversation.domain.generation;

import org.junit.jupiter.api.Test;

import static io.github.yanhuo218.autumnwind.conversation.domain.generation.GenerationStatus.FAILED;
import static io.github.yanhuo218.autumnwind.conversation.domain.generation.GenerationStatus.INTERRUPTED;
import static io.github.yanhuo218.autumnwind.conversation.domain.generation.GenerationStatus.PENDING;
import static io.github.yanhuo218.autumnwind.conversation.domain.generation.GenerationStatus.STOPPED;
import static io.github.yanhuo218.autumnwind.conversation.domain.generation.GenerationStatus.STREAMING;
import static io.github.yanhuo218.autumnwind.conversation.domain.generation.GenerationStatus.SUCCEEDED;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationStatusTest {

    @Test
    void 只允许设计内的生成状态转换() {
        assertTrue(PENDING.canTransitionTo(STREAMING));
        assertTrue(PENDING.canTransitionTo(FAILED));
        assertTrue(PENDING.canTransitionTo(STOPPED));
        assertTrue(PENDING.canTransitionTo(INTERRUPTED));
        assertTrue(STREAMING.canTransitionTo(SUCCEEDED));
        assertTrue(STREAMING.canTransitionTo(FAILED));
        assertTrue(STREAMING.canTransitionTo(STOPPED));
        assertTrue(STREAMING.canTransitionTo(INTERRUPTED));
        assertFalse(STREAMING.canTransitionTo(PENDING));
        assertFalse(SUCCEEDED.canTransitionTo(FAILED));
        assertFalse(FAILED.canTransitionTo(STREAMING));
        assertFalse(STOPPED.canTransitionTo(STOPPED));
        assertFalse(INTERRUPTED.canTransitionTo(SUCCEEDED));
    }

    @Test
    void 终态不可再转换() {
        assertTrue(SUCCEEDED.terminal());
        assertTrue(FAILED.terminal());
        assertTrue(STOPPED.terminal());
        assertTrue(INTERRUPTED.terminal());
        assertFalse(PENDING.terminal());
        assertFalse(STREAMING.terminal());
    }

    @Test
    void 非法转换抛出稳定领域异常() {
        assertThrows(IllegalStateException.class,
                () -> SUCCEEDED.requireTransitionTo(STREAMING));
        assertThrows(NullPointerException.class,
                () -> PENDING.requireTransitionTo(null));
    }
}
