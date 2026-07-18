import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { compile } from 'json-schema-to-typescript';
import openapiTS, { astToString } from 'openapi-typescript';

const sources = {
  conversation: 'contracts/openapi/conversation.openapi.json',
  modelRegistry: 'contracts/openapi/model-registry.openapi.json',
  stream: 'contracts/events/conversation-stream-event.v1.schema.json'
};

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, '..');
const generatedDirectory = path.join(repositoryRoot, 'packages/api-contracts/src/generated');

async function readJson(relativePath) {
  return JSON.parse(await readFile(path.join(repositoryRoot, relativePath), 'utf8'));
}

function escapeJsonPointerSegment(value) {
  return value.replaceAll('~', '~0').replaceAll('/', '~1');
}

function rewriteSchemaRefs(value, namespace) {
  if (Array.isArray(value)) {
    return value.map((item) => rewriteSchemaRefs(item, namespace));
  }

  if (value === null || typeof value !== 'object') {
    return value;
  }

  return Object.fromEntries(
    Object.entries(value).map(([key, item]) => {
      if (key === '$ref' && typeof item === 'string' && item.startsWith('#/components/schemas/')) {
        const schemaName = item.slice('#/components/schemas/'.length);
        return [key, `#/$defs/${namespace}.${escapeJsonPointerSegment(schemaName)}`];
      }
      return [key, rewriteSchemaRefs(item, namespace)];
    })
  );
}

function copyComponentSchemas(document, namespace) {
  const schemas = document.components?.schemas;
  if (schemas === undefined || schemas === null || typeof schemas !== 'object') {
    throw new Error(`契约缺少 components.schemas：${namespace}`);
  }

  return Object.fromEntries(
    Object.entries(schemas).map(([name, schema]) => [
      `${namespace}.${name}`,
      rewriteSchemaRefs(schema, namespace)
    ])
  );
}

function createHttpResponseSchema(conversation, modelRegistry) {
  const reference = (namespace, name) => ({ $ref: `#/$defs/${namespace}.${name}` });

  return {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    $id: 'https://autumn-wind.ai/contracts/frontend/http-response.schema.json',
    $defs: {
      ...copyComponentSchemas(conversation, 'conversation'),
      ...copyComponentSchemas(modelRegistry, 'modelRegistry'),
      ConversationListView: reference('conversation', 'ConversationListView'),
      ConversationDetailView: reference('conversation', 'ConversationDetailView'),
      ConversationView: reference('conversation', 'ConversationView'),
      GenerationAcceptedView: reference('conversation', 'GenerationAcceptedView'),
      GenerationView: reference('conversation', 'GenerationView'),
      ConversationErrorResponse: reference('conversation', 'ErrorResponse'),
      ModelViewList: {
        type: 'array',
        items: reference('modelRegistry', 'ModelView')
      },
      ModelRegistryErrorResponse: reference('modelRegistry', 'ErrorResponse')
    }
  };
}

async function generateOpenApiTypes(document, outputName) {
  const ast = await openapiTS(document);
  await writeFile(path.join(generatedDirectory, outputName), astToString(ast), 'utf8');
}

async function main() {
  const [conversation, modelRegistry, stream] = await Promise.all([
    readJson(sources.conversation),
    readJson(sources.modelRegistry),
    readJson(sources.stream)
  ]);

  await mkdir(generatedDirectory, { recursive: true });
  await Promise.all([
    generateOpenApiTypes(conversation, 'conversation.ts'),
    generateOpenApiTypes(modelRegistry, 'model-registry.ts'),
    writeFile(
      path.join(generatedDirectory, 'conversation-stream-event.ts'),
      await compile(stream, 'ConversationStreamEventV1', {
        bannerComment: '/* 此文件由契约生成脚本自动生成，请勿手工修改。 */'
      }),
      'utf8'
    ),
    writeFile(
      path.join(generatedDirectory, 'conversation-stream-event.schema.json'),
      `${JSON.stringify(stream, null, 2)}\n`,
      'utf8'
    ),
    writeFile(
      path.join(generatedDirectory, 'http-response.schema.json'),
      `${JSON.stringify(createHttpResponseSchema(conversation, modelRegistry), null, 2)}\n`,
      'utf8'
    )
  ]);
}

await main();
