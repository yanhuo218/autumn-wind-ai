export interface paths {
    "/api/v1/conversations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** 列出会话 */
        get: operations["listConversations"];
        put?: never;
        /** 创建会话 */
        post: operations["createConversation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{conversationId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                conversationId: components["parameters"]["ConversationId"];
            };
            cookie?: never;
        };
        /** 读取会话详情 */
        get: operations["getConversation"];
        put?: never;
        post?: never;
        /** 归档会话 */
        delete: operations["archiveConversation"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{conversationId}/generations": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                conversationId: components["parameters"]["ConversationId"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * 创建生成
         * @description 同一会话内重复提交相同 clientRequestId 时返回原生成，不重复启动推理。
         */
        post: operations["createGeneration"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/generations/{generationId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                generationId: components["parameters"]["GenerationId"];
            };
            cookie?: never;
        };
        /** 读取生成快照 */
        get: operations["getGeneration"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/generations/{generationId}/events": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                generationId: components["parameters"]["GenerationId"];
            };
            cookie?: never;
        };
        /**
         * 订阅生成事件
         * @description 返回标准 SSE 帧。网关和反向代理必须禁用响应缓冲，并及时向客户端刷新每个事件。客户端可使用 Last-Event-ID 断点续传。
         */
        get: operations["streamGenerationEvents"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/generations/{generationId}/stop": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                generationId: components["parameters"]["GenerationId"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** 停止生成 */
        post: operations["stopGeneration"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/generations/{generationId}/regenerate": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                generationId: components["parameters"]["GenerationId"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** 重新生成 */
        post: operations["regenerateGeneration"];
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
        ConversationCreateRequest: {
            title?: string;
        };
        GenerationCreateRequest: {
            /** Format: uuid */
            clientRequestId: string;
            /** Format: uuid */
            modelId: string;
            content: components["schemas"]["MessageContent"];
        };
        RegenerateRequest: {
            /** Format: uuid */
            clientRequestId: string;
        };
        MessageContent: {
            /** @constant */
            schemaVersion: 1;
            blocks: components["schemas"]["ContentBlock"][];
        };
        ContentBlock: components["schemas"]["TextContentBlock"] | components["schemas"]["ImageReferenceContentBlock"] | components["schemas"]["FileReferenceContentBlock"];
        TextContentBlock: {
            /** @constant */
            type: "text";
            text: string;
        };
        ImageReferenceContentBlock: {
            /** @constant */
            type: "image_ref";
            /** Format: uuid */
            resourceId: string;
        };
        FileReferenceContentBlock: {
            /** @constant */
            type: "file_ref";
            /** Format: uuid */
            resourceId: string;
        };
        /** @enum {string} */
        GenerationStatus: "PENDING" | "STREAMING" | "SUCCEEDED" | "FAILED" | "STOPPED" | "INTERRUPTED";
        ConversationView: {
            /** Format: uuid */
            conversationId: string;
            title: string;
            /** Format: date-time */
            createdAt: string;
            archived: boolean;
        } & {
            [key: string]: unknown;
        };
        ConversationListView: {
            items: components["schemas"]["ConversationView"][];
        } & {
            [key: string]: unknown;
        };
        ConversationDetailView: {
            /** Format: uuid */
            conversationId: string;
            title: string;
            /** Format: date-time */
            createdAt: string;
            archived: boolean;
            generations: components["schemas"]["GenerationView"][];
            messages: components["schemas"]["MessageView"][];
        } & {
            [key: string]: unknown;
        };
        /** @enum {string} */
        MessageRole: "USER" | "ASSISTANT";
        /** @enum {string} */
        MessageCompleteness: "COMPLETE" | "PARTIAL";
        MessageView: ({
            /** Format: uuid */
            messageId: string;
            role: components["schemas"]["MessageRole"];
            content: components["schemas"]["MessageContent"];
            completeness: components["schemas"]["MessageCompleteness"];
            generationId: string | null;
            /** Format: date-time */
            createdAt: string;
        } & {
            [key: string]: unknown;
        }) & ({
            /** @constant */
            role?: "USER";
            generationId?: null;
        } | {
            /** @constant */
            role?: "ASSISTANT";
            /** Format: uuid */
            generationId?: string;
        });
        GenerationAcceptedView: {
            /** Format: uuid */
            userMessageId: string;
            /** Format: uuid */
            generationId: string;
            /** Format: uri-reference */
            statusUrl: string;
            /** Format: uri-reference */
            eventsUrl: string;
        } & {
            [key: string]: unknown;
        };
        GenerationView: {
            /** Format: uuid */
            generationId: string;
            /** Format: uuid */
            conversationId: string;
            status: components["schemas"]["GenerationStatus"];
            content: components["schemas"]["MessageContent"];
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            updatedAt: string;
            error?: components["schemas"]["ErrorResponse"] | null;
        } & {
            [key: string]: unknown;
        };
        ErrorResponse: {
            code: string;
            message: string;
            /** Format: uuid */
            correlationId: string;
        } & {
            [key: string]: unknown;
        };
    };
    responses: {
        /** @description 请求无效。 */
        BadRequest: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 未认证。 */
        Unauthorized: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 无权执行。 */
        Forbidden: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 资源不存在。 */
        NotFound: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 资源状态冲突。 */
        Conflict: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 不支持请求的响应格式。 */
        NotAcceptable: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 不支持请求体媒体类型。 */
        UnsupportedMediaType: {
            headers: {
                "X-Correlation-ID": components["headers"]["CorrelationId"];
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["ErrorResponse"];
            };
        };
        /** @description 服务内部错误。 */
        InternalServerError: {
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
        ConversationId: string;
        GenerationId: string;
    };
    requestBodies: never;
    headers: {
        /** @description 端到端关联 ID。 */
        CorrelationId: string;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listConversations: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 会话列表。 */
            200: {
                headers: {
                    "X-Correlation-ID": components["headers"]["CorrelationId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConversationListView"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            406: components["responses"]["NotAcceptable"];
            500: components["responses"]["InternalServerError"];
        };
    };
    createConversation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ConversationCreateRequest"];
            };
        };
        responses: {
            /** @description 会话已创建。 */
            201: {
                headers: {
                    "X-Correlation-ID": components["headers"]["CorrelationId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConversationView"];
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
    getConversation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                conversationId: components["parameters"]["ConversationId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 会话详情。 */
            200: {
                headers: {
                    "X-Correlation-ID": components["headers"]["CorrelationId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConversationDetailView"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            406: components["responses"]["NotAcceptable"];
            500: components["responses"]["InternalServerError"];
        };
    };
    archiveConversation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                conversationId: components["parameters"]["ConversationId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 会话已归档。 */
            204: {
                headers: {
                    "X-Correlation-ID": components["headers"]["CorrelationId"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            406: components["responses"]["NotAcceptable"];
            500: components["responses"]["InternalServerError"];
        };
    };
    createGeneration: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                conversationId: components["parameters"]["ConversationId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["GenerationCreateRequest"];
            };
        };
        responses: {
            /** @description 生成已接受。 */
            202: {
                headers: {
                    "X-Correlation-ID": components["headers"]["CorrelationId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GenerationAcceptedView"];
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
    getGeneration: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                generationId: components["parameters"]["GenerationId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 生成快照。 */
            200: {
                headers: {
                    "X-Correlation-ID": components["headers"]["CorrelationId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GenerationView"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            406: components["responses"]["NotAcceptable"];
            500: components["responses"]["InternalServerError"];
        };
    };
    streamGenerationEvents: {
        parameters: {
            query?: never;
            header?: {
                /** @description 客户端最后成功处理的事件 ID；服务端仅重放其后的事件。 */
                "Last-Event-ID"?: string;
            };
            path: {
                generationId: components["parameters"]["GenerationId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description SSE 事件流；代理必须禁用缓冲并保持流式刷新。 */
            200: {
                headers: {
                    "X-Correlation-ID": components["headers"]["CorrelationId"];
                    /** @description 禁止缓存事件流。 */
                    "Cache-Control"?: "no-cache";
                    /** @description 明确禁用兼容代理的响应缓冲。 */
                    "X-Accel-Buffering"?: "no";
                    [name: string]: unknown;
                };
                content: {
                    "text/event-stream": string;
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            406: components["responses"]["NotAcceptable"];
            500: components["responses"]["InternalServerError"];
        };
    };
    stopGeneration: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                generationId: components["parameters"]["GenerationId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description 停止后的生成快照。 */
            200: {
                headers: {
                    "X-Correlation-ID": components["headers"]["CorrelationId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GenerationView"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            406: components["responses"]["NotAcceptable"];
            409: components["responses"]["Conflict"];
            500: components["responses"]["InternalServerError"];
        };
    };
    regenerateGeneration: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                generationId: components["parameters"]["GenerationId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RegenerateRequest"];
            };
        };
        responses: {
            /** @description 新生成已接受。 */
            202: {
                headers: {
                    "X-Correlation-ID": components["headers"]["CorrelationId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GenerationAcceptedView"];
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
