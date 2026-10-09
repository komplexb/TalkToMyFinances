# Container image for the Cloud Run deployment of the local BankSync mock.
#
# Only the mock's runtime dependencies are needed in the image
# (the client/auditor run elsewhere, locally or in their own service).
# `uv sync --no-dev` skips the dev group (pytest/jsonschema); the lockfile
# keeps the image on the exact versions the tests ran against locally.
#
# Build locally to smoke-test (any key works locally; unset key rejects all):
#   docker build -t ttmf-banksync-mock .
#   docker run --rm -p 8000:8000 -e BANKSYNC_MOCK_KEY=local-dev ttmf-banksync-mock

FROM ghcr.io/astral-sh/uv:python3.11-trixie-slim

WORKDIR /service
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

# Lockfile-first: exact pinned deps, no re-resolve at build time.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

COPY mock_banksync/ ./mock_banksync/

# Cloud Run sets PORT (default 8080); fall back to 8000 for local `docker run`.
EXPOSE 8000
CMD uv run uvicorn mock_banksync.main:app --host 0.0.0.0 --port ${PORT:-8000}
