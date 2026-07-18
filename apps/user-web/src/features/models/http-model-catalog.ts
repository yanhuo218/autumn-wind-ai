import {
  isModelViewList
} from '@autumn-wind/api-contracts';

import { fetchJson } from '../../lib/fetch-json';
import { filterAvailableTextModels, type ModelCatalog } from './model-catalog';

export function createHttpModelCatalog(fetchImpl: typeof fetch = fetch): ModelCatalog {
  return {
    async listAvailableTextModels(signal) {
      const models = await fetchJson(
        fetchImpl,
        '/api/v1/model-registry/models',
        isModelViewList,
        { signal }
      );
      return filterAvailableTextModels(models);
    }
  };
}
