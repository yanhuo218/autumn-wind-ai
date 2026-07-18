/* 此文件由契约生成脚本自动生成，请勿手工修改。 */

/**
 * Conversation 生成流的 SSE data 信封。
 */
export type ConversationStreamEventV1 =
  | {
      eventId: EventId;
      eventType: "generation.started";
      generationId: GenerationId;
      sequence: Sequence;
      occurredAt: OccurredAt;
      payloadVersion: PayloadVersion;
      payload: {
        status: "STREAMING";
      };
    }
  | {
      eventId: EventId;
      eventType: "reasoning.delta";
      generationId: GenerationId;
      sequence: Sequence;
      occurredAt: OccurredAt;
      payloadVersion: PayloadVersion;
      payload: {
        delta: string;
      };
    }
  | {
      eventId: EventId;
      eventType: "content.delta";
      generationId: GenerationId;
      sequence: Sequence;
      occurredAt: OccurredAt;
      payloadVersion: PayloadVersion;
      payload: {
        delta: string;
      };
    }
  | {
      eventId: EventId;
      eventType: "content.checkpoint";
      generationId: GenerationId;
      sequence: Sequence;
      occurredAt: OccurredAt;
      payloadVersion: PayloadVersion;
      payload: {
        content: MessageContent;
        throughSequence: number;
      };
    }
  | {
      eventId: EventId;
      eventType: "usage.updated";
      generationId: GenerationId;
      sequence: Sequence;
      occurredAt: OccurredAt;
      payloadVersion: PayloadVersion;
      payload: {
        promptTokens: NullableTokenCount;
        completionTokens: NullableTokenCount;
        totalTokens: NullableTokenCount;
      };
    }
  | {
      eventId: EventId;
      eventType: "generation.completed";
      generationId: GenerationId;
      sequence: Sequence;
      occurredAt: OccurredAt;
      payloadVersion: PayloadVersion;
      payload: {
        status: "SUCCEEDED";
      };
    }
  | {
      eventId: EventId;
      eventType: "generation.failed";
      generationId: GenerationId;
      sequence: Sequence;
      occurredAt: OccurredAt;
      payloadVersion: PayloadVersion;
      payload: {
        status: "FAILED";
        code: ErrorCode;
        message: string;
        correlationId: string;
      };
    }
  | {
      eventId: EventId;
      eventType: "generation.stopped";
      generationId: GenerationId;
      sequence: Sequence;
      occurredAt: OccurredAt;
      payloadVersion: PayloadVersion;
      payload: {
        status: "STOPPED";
      };
    }
  | {
      eventId: EventId;
      eventType: "generation.interrupted";
      generationId: GenerationId;
      sequence: Sequence;
      occurredAt: OccurredAt;
      payloadVersion: PayloadVersion;
      payload: {
        status: "INTERRUPTED";
        code: ErrorCode;
        message: string;
        correlationId: string;
      };
    }
  | {
      eventId: EventId;
      eventType: "stream.heartbeat";
      generationId: GenerationId;
      sequence: Sequence;
      occurredAt: OccurredAt;
      payloadVersion: PayloadVersion;
      payload: {};
    }
  | {
      eventId: EventId;
      eventType: "replay.reset";
      generationId: GenerationId;
      sequence: Sequence;
      occurredAt: OccurredAt;
      payloadVersion: PayloadVersion;
      /**
       * 客户端收到后必须用快照替换本地生成内容。
       */
      payload: {
        snapshotUrl: string;
      };
    };
export type EventId = string;
export type GenerationId = string;
export type Sequence = number;
export type OccurredAt = string;
export type PayloadVersion = 1;
export type NullableTokenCount = number | null;
export type ErrorCode = string;

export interface MessageContent {
  schemaVersion: 1;
  /**
   * @minItems 1
   * @maxItems 100
   */
  blocks: [
    (
      | {
          type: "text";
          text: string;
        }
      | {
          type: "image_ref";
          resourceId: string;
        }
      | {
          type: "file_ref";
          resourceId: string;
        }
    ),
    ...(
      | {
          type: "text";
          text: string;
        }
      | {
          type: "image_ref";
          resourceId: string;
        }
      | {
          type: "file_ref";
          resourceId: string;
        }
    )[]
  ];
}
