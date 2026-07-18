export class HttpError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;

  constructor(status: number, code: string, message: string, correlationId?: string) {
    super(message);
    this.name = 'HttpError';
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }
}
