import Ajv2020, { type ValidateFunction } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

import httpResponseSchema from './generated/http-response.schema.json';
import type {
  ConversationDetailView,
  ConversationListView,
  ConversationView,
  GenerationAcceptedView,
  GenerationView,
  ModelView,
  PublicErrorResponse
} from './index';

const ajv = new Ajv2020({ strict: true });
addFormats(ajv);
ajv.addFormat('int64', {
  type: 'number',
  validate: Number.isSafeInteger
});
ajv.addSchema(httpResponseSchema);

const schemaId = httpResponseSchema.$id;

function compileDefinition(name: string): ValidateFunction {
  return ajv.compile({ $ref: `${schemaId}#/$defs/${name}` });
}

const validateConversationListView = compileDefinition('ConversationListView');
const validateConversationDetailView = compileDefinition('ConversationDetailView');
const validateConversationView = compileDefinition('ConversationView');
const validateGenerationAcceptedView = compileDefinition('GenerationAcceptedView');
const validateGenerationView = compileDefinition('GenerationView');
const validateModelViewList = compileDefinition('ModelViewList');
const validateConversationErrorResponse = compileDefinition('ConversationErrorResponse');
const validateModelRegistryErrorResponse = compileDefinition('ModelRegistryErrorResponse');

export function isConversationListView(value: unknown): value is ConversationListView {
  return validateConversationListView(value);
}

export function isConversationDetailView(value: unknown): value is ConversationDetailView {
  return validateConversationDetailView(value);
}

export function isConversationView(value: unknown): value is ConversationView {
  return validateConversationView(value);
}

export function isGenerationAcceptedView(value: unknown): value is GenerationAcceptedView {
  return validateGenerationAcceptedView(value);
}

export function isGenerationView(value: unknown): value is GenerationView {
  return validateGenerationView(value);
}

export function isModelViewList(value: unknown): value is ModelView[] {
  return validateModelViewList(value);
}

export function isPublicErrorResponse(value: unknown): value is PublicErrorResponse {
  return validateConversationErrorResponse(value) || validateModelRegistryErrorResponse(value);
}
