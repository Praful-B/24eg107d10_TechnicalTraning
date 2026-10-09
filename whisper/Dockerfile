FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HEARTBEAT_FILE=/tmp/worker.healthy

RUN pip install "uv==0.12.9"

WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
COPY src ./src
RUN uv sync --frozen --no-dev

ENV PATH="/app/.venv/bin:$PATH"

# The worker writes HEARTBEAT_FILE on a timer, so a hung inference is visible.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD test -f "$HEARTBEAT_FILE" || exit 1

# The console script exposes whisper:main, which wires up the consumer.
CMD ["whisper"]
