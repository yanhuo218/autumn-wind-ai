export interface paths {
    "/api/v1/model-registry/endpoints": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listEndpoints"];
        put?: never;
        post: operations["createEndpoint"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/model-registry/endpoints/{endpointId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                endpointId: components["parameters"]["EndpointId"];
            };
            cookie?: never;
        };
        get: operations["getEndpoint"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/model-registry/endpoints/{endpointId}/credential": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                endpointId: components["parameters"]["EndpointId"];
            };
            cookie?: never;
        };
        get?: never;
        put: operations["replaceEndpointCredential"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/model-registry/endpoints/{endpointId}/connection-tests": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                endpointId: components["parameters"]["EndpointId"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description 只创建持久化任务；外部网络请求由 Inference Gateway 执行。 */
        post: operations["createEndpointConnectionTest"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/model-registry/models": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listModels"];
        put?: never;
        post: operations["createModel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/model-registry/models/{modelId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                modelId: components["parameters"]["ModelId"];
            };
            cookie?: never;
        };
        get: operations["getModel"];
        put: operations["updateModel"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        EndpointCreateRequest: {
            displayName: string;
            /** Format: uri */
            baseUrl: string;
            /** @enum {string} */
            protocol: "OPENAI_COMPATIBLE";
            requestTimeoutSeconds: number;
            enabled: boolean;
            apiKey: string;
        };
        EndpointCredentialReplaceRequest: {
            apiKey: string;
            /** Format: int64 */
            expectedVersion: number;
        };
        EndpointView: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ownerUserId: string;
            displayName: string;
            /** Format: uri */
            baseUrl: string;
            /** @enum {string} */
            protocol: "OPENAI_COMPATIBLE";
            requestTimeoutSeconds: number;
            enabled: boolean;
            credentialConfigured: boolean;
            /** Format: int64 */
            version: number;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            updatedAt: string;
        };
        EndpointConnectionTestRequest: {
            /** Format: int64 */
            expectedVersion: number;
        };
        EndpointConnectionTestView: {
            /** Format: uuid */
            jobId: string;
            /** Format: uuid */
            endpointId: string;
            /** @enum {string} */
            status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
            /** Format: int64 */
            endpointVersion: number;
            /** Format: date-time */
            createdAt: string;
        };
        ModelCapabilities: {
            /** @enum {string} */
            interfaceType: "CHAT_COMPLETIONS" | "IMAGE_GENERATION";
            inputModalities: ("TEXT" | "IMAGE" | "FILE" | "VIDEO")[];
            /** @enum {string} */
            outputModality: "TEXT" | "IMAGE";
            streaming: boolean;
            systemPrompt: boolean;
            reasoning: boolean;
            contextLength: number;
            maxOutputLength: number;
        };
        ModelCreateRequest: {
            /** Format: uuid */
            endpointId: string;
            providerModelId: string;
            displayName: string;
            capabilities: components["schemas"]["ModelCapabilities"];
            enabled: boolean;
            defaultModel: boolean;
        };
        ModelUpdateRequest: {
            providerModelId: string;
            displayName: string;
            capabilities: components["schemas"]["ModelCapabilities"];
            enabled: boolean;
            defaultModel: boolean;
            /** Format: int64 */
            expectedVersion: number;
        };
        ModelView: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            ownerUserId: string;
            /** Format: uuid */
            endpointId: string;
            providerModelId: string;
            displayName: string;
            capabilities: components["schemas"]["ModelCapabilities"];
            enabled: boolean;
            defaultModel: boolean;
            capabilitySchemaVersion: number;
            /** Format: int64 */
            version: number;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            updatedAt: string;
        };
        ErrorResponse: {
            code: string;
            message: string;
            correlationId: string;
            fieldErrors?: Record<string, never>[];
        } & {
            [key: string]: unknown;
        };
    };
    responses: {
        BadRequest: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        Unauthorized: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        Forbidden: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        NotFound: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        Conflict: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        NotAcceptable: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        UnsupportedMediaType: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        InternalServerError: {
            headers: {
                [name: string]: unknown;
            };
            content?: never;
        };
        /** @description 请求不合法。 */
        Error400: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description Service JWT 无效或缺失。 */
        Error401: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 无权访问。 */
        Error403: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 资源不存在。 */
        Error404: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 响应媒体类型不受支持。 */
        Error406: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 资源状态或版本冲突。 */
        Error409: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 请求媒体类型不受支持。 */
        Error415: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 服务器内部错误。 */
        Error500: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
    };
    parameters: {
        EndpointId: string;
        ModelId: string;
    };
    requestBodies: never;
    headers: {
        /** @description 请求关联标识。 */
        CorrelationId: string;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listEndpoints: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 当前用户的端点列表。 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EndpointView"][];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            406: components["responses"]["NotAcceptable"];
            500: components["responses"]["InternalServerError"];
        };
    };
    createEndpoint: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["EndpointCreateRequest"];
            };
        };
        responses: {
            /** @description 端点创建成功。 */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EndpointView"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            406: components["responses"]["NotAcceptable"];
            415: components["responses"]["UnsupportedMediaType"];
            500: components["responses"]["InternalServerError"];
        };
    };
    getEndpoint: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                endpointId: components["parameters"]["EndpointId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 端点详情。 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EndpointView"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            406: components["responses"]["NotAcceptable"];
            500: components["responses"]["InternalServerError"];
        };
    };
    replaceEndpointCredential: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                endpointId: components["parameters"]["EndpointId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["EndpointCredentialReplaceRequest"];
            };
        };
        responses: {
            /** @description 凭据替换成功。 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EndpointView"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            406: components["responses"]["NotAcceptable"];
            409: components["responses"]["Conflict"];
            415: components["responses"]["UnsupportedMediaType"];
            500: components["responses"]["InternalServerError"];
        };
    };
    createEndpointConnectionTest: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                endpointId: components["parameters"]["EndpointId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["EndpointConnectionTestRequest"];
            };
        };
        responses: {
            /** @description 连接测试任务已入队。 */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EndpointConnectionTestView"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            406: components["responses"]["NotAcceptable"];
            409: components["responses"]["Conflict"];
            415: components["responses"]["UnsupportedMediaType"];
            500: components["responses"]["InternalServerError"];
        };
    };
    listModels: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 当前用户的模型列表。 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ModelView"][];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            406: components["responses"]["NotAcceptable"];
            500: components["responses"]["InternalServerError"];
        };
    };
    createModel: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ModelCreateRequest"];
            };
        };
        responses: {
            /** @description 模型创建成功。 */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ModelView"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            406: components["responses"]["NotAcceptable"];
            409: components["responses"]["Conflict"];
            415: components["responses"]["UnsupportedMediaType"];
            500: components["responses"]["InternalServerError"];
        };
    };
    getModel: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                modelId: components["parameters"]["ModelId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 模型详情。 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ModelView"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            406: components["responses"]["NotAcceptable"];
            500: components["responses"]["InternalServerError"];
        };
    };
    updateModel: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                modelId: components["parameters"]["ModelId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ModelUpdateRequest"];
            };
        };
        responses: {
            /** @description 模型更新成功。 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ModelView"];
                };
            };
            400: components["responses"]["BadRequest"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            406: components["responses"]["NotAcceptable"];
            409: components["responses"]["Conflict"];
            415: components["responses"]["UnsupportedMediaType"];
            500: components["responses"]["InternalServerError"];
        };
    };
}
