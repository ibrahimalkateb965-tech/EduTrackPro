# EduTrack Pro API — production image (FastAPI + uvicorn, deps pinned by uv.lock)
FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:0.11 /uv /usr/local/bin/uv

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PYTHONUNBUFFERED=1 \
    PATH="/app/.venv/bin:$PATH"

WORKDIR /app

# Dependencies first so source edits do not invalidate the layer.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

# Headless Chromium for /api/v1/print/pdf/* (services/pdf.py), outside /app so the
# non-root user can read it; --with-deps pulls its system libraries.
RUN playwright install --with-deps --only-shell chromium && rm -rf /var/lib/apt/lists/*

COPY edutrack_api ./edutrack_api
RUN uv sync --frozen --no-dev

RUN useradd --system --uid 10001 --no-create-home edutrack && \
    mkdir -p /app/uploads /app/student_photos && \
    chown -R edutrack:edutrack /app/uploads /app/student_photos
USER edutrack

EXPOSE 8000
CMD ["uvicorn", "edutrack_api.main:app", "--host", "0.0.0.0", "--port", "8000", \
     "--proxy-headers", "--forwarded-allow-ips", "*"]
