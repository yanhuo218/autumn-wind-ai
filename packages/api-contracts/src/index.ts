import type { components as ConversationComponents } from './generated/conversation';
import type { components as ModelRegistryComponents } from './generated/model-registry';

export type { ConversationStreamEventV1 } from './generated/conversation-stream-event';
export {
  isConversationStreamEvent
} from './validate-conversation-event';
export {
  isConversationDetailView,
  isConversationListView,
  isConversationView,
  isGenerationAcceptedView,
  isGenerationView,
  isModelViewList,
  isPublicErrorResponse
} from './validate-http-response';

type ConversationSchemas = ConversationComponents['schemas'];
type ModelRegistrySchemas = ModelRegistryComponents['schemas'];

export type ConversationCreateRequest = ConversationSchemas['ConversationCreateRequest'];
export type ConversationListView = ConversationSchemas['ConversationListView'];
export type ConversationDetailView = ConversationSchemas['ConversationDetailView'];
export type ConversationView = ConversationSchemas['ConversationView'];
export type GenerationCreateRequest = ConversationSchemas['GenerationCreateRequest'];
export type GenerationAcceptedView = ConversationSchemas['GenerationAcceptedView'];
export type GenerationView = ConversationSchemas['GenerationView'];
export type RegenerateRequest = ConversationSchemas['RegenerateRequest'];
export type MessageContent = ConversationSchemas['MessageContent'];
export type MessageView = ConversationSchemas['MessageView'];
export type ModelView = ModelRegistrySchemas['ModelView'];
export type ConversationErrorResponse = ConversationSchemas['ErrorResponse'];
export type ModelRegistryErrorResponse = ModelRegistrySchemas['ErrorResponse'];
export type PublicErrorResponse = ConversationErrorResponse | ModelRegistryErrorResponse;
