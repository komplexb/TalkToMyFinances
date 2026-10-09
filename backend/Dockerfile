# Container image for the Cloud Run deployment of the local BankSync mock.
#
# Only the mock's runtime dependencies are needed in the image
# (the client/auditor run elsewhere, locally or in their own service).
#
# Build locally to smoke-test:
#   docker build -t ttmf-banksync-mock .
#   docker run --rm -p 8000:8000 -e BANKSYNC_MOCK_KEY=mock-key ttmf-banksync-mock

FROM python:3.11-slim

WORKDIR /service
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# Pinned to the same ranges as pyproject.toml; keep in sync.
RUN pip install \
    "fastapi>=0.115,<1" \
    "uvicorn>=0.30,<1" \
    "python-dotenv>=1,<2"

COPY mock_banksync/ ./mock_banksync/

# Cloud Run sets PORT (default 8080); fall back to 8000 for local `docker run`.
EXPOSE 8000
CMD uvicorn mock_banksync.main:app --host 0.0.0.0 --port ${PORT:-8000}
