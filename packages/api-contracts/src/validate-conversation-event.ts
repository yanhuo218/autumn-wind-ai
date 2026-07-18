import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

import type { ConversationStreamEventV1 } from './generated/conversation-stream-event';
import conversationStreamEventSchema from './generated/conversation-stream-event.schema.json';

const ajv = new Ajv2020({ strict: true });
addFormats(ajv);

const validateConversationStreamEvent = ajv.compile(conversationStreamEventSchema);

export function isConversationStreamEvent(value: unknown): value is ConversationStreamEventV1 {
  return validateConversationStreamEvent(value);
}
