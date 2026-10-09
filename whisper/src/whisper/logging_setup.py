"""Structured JSON logging so every worker line can be traced back to a job and chunk."""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime, timezone

# Extra fields promoted from `logging`'s `extra=` into the JSON object.
_EXTRA_FIELDS = ("jobId", "chunkIndex", "attempt", "metrics")


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "time": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        for field in _EXTRA_FIELDS:
            value = getattr(record, field, None)
            if value is not None:
                payload[field] = value
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload)


def configure_logging(level: str = "INFO") -> logging.Logger:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())

    # pika is chatty at DEBUG; keep the worker output readable.
    logging.getLogger("pika").setLevel(logging.WARNING)
    return logging.getLogger("whisper")
