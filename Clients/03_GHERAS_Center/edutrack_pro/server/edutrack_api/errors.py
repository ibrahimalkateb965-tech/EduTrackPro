"""Central API error types, DB error mapping, and FastAPI exception handlers."""

from __future__ import annotations

import logging

import psycopg
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger(__name__)

DEFAULT_MESSAGES: dict[int, str] = {
    401: "يجب تسجيل الدخول",
    403: "غير مصرح لك بهذه العملية",
    404: "العنصر غير موجود",
    409: "السجل موجود مسبقاً",
    422: "بيانات غير صالحة",
    500: "حدث خطأ غير متوقع",
}

HTTP_STATUS_CODES: dict[int, str] = {
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    405: "method_not_allowed",
}


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str | None = None) -> None:
        self.status = status
        self.code = code
        self.message = message or DEFAULT_MESSAGES.get(status, "حدث خطأ غير متوقع")
        super().__init__(self.message)

    def to_response(self) -> JSONResponse:
        return JSONResponse(
            status_code=self.status,
            content={"error": {"code": self.code, "message": self.message}},
        )


def _error_response(status: int, code: str, message: str | None = None) -> JSONResponse:
    return ApiError(status, code, message).to_response()


def map_db_error(exc: psycopg.Error) -> ApiError:
    diag = getattr(exc, "diag", None)
    constraint = getattr(diag, "constraint_name", None)
    column = getattr(diag, "column_name", None)
    suffix = ""
    if constraint or column:
        suffix = " (" + (constraint or column or "") + ")"
    if isinstance(exc, psycopg.errors.UniqueViolation):
        return ApiError(409, "conflict")
    if isinstance(
        exc,
        (
            psycopg.errors.CheckViolation,
            psycopg.errors.NotNullViolation,
            psycopg.errors.ForeignKeyViolation,
            psycopg.errors.InvalidTextRepresentation,
            psycopg.errors.InvalidDatetimeFormat,
            psycopg.errors.NumericValueOutOfRange,
        ),
    ):
        return ApiError(422, "validation_error", "بيانات غير صالحة" + suffix)
    return ApiError(500, "internal_error")


def register_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def handle_api_error(_request, exc: ApiError) -> JSONResponse:
        return exc.to_response()

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(_request, _exc: RequestValidationError) -> JSONResponse:
        return _error_response(422, "validation_error")

    @app.exception_handler(StarletteHTTPException)
    async def handle_http_exception(_request, exc: StarletteHTTPException) -> JSONResponse:
        code = HTTP_STATUS_CODES.get(exc.status_code, "http_error")
        return _error_response(exc.status_code, code)

    @app.exception_handler(psycopg.Error)
    async def handle_db_error(_request, exc: psycopg.Error) -> JSONResponse:
        return map_db_error(exc).to_response()

    @app.exception_handler(Exception)
    async def handle_unexpected_error(_request, exc: Exception) -> JSONResponse:
        logger.exception("Unhandled exception: %s", exc)
        return _error_response(500, "internal_error")
