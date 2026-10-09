"""RabbitMQ consumer that transcribes audio chunks with Whisper.

Features:
- Automatic reconnection with exponential backoff
- Retry logic with delay queue (up to MAX_ATTEMPTS)
- Heartbeat file for Docker health checks
- Graceful shutdown on SIGTERM/SIGINT
"""

from __future__ import annotations

import json
import os
import signal
import sys
import threading
import time
import traceback
from typing import Any, Dict, Optional

import pika
from pika.adapters.blocking_connection import BlockingChannel, BlockingConnection
from pika.exceptions import AMQPConnectionError, ChannelClosedByBroker, ConnectionClosedByBroker
from pika.spec import Basic, BasicProperties

from .logging_setup import configure_logging
from .transcribe import Transcriber

# Configuration from environment variables
RABBITMQ_HOST = os.getenv("RABBITMQ_HOST", "localhost")
RABBITMQ_PORT = int(os.getenv("RABBITMQ_PORT", "5672"))
RABBITMQ_USER = os.getenv("RABBITMQ_USER", "guest")
RABBITMQ_PASS = os.getenv("RABBITMQ_PASS", "guest")

# Queue configuration - must match backend RabbitMQConfiguration
PREPROCESSING_QUEUE = os.getenv("PREPROCESSING_QUEUE", "preprocessing_queue")
EXCHANGE = os.getenv("EXCHANGE", "processing_exchange")
POSTPROCESSING_QUEUE = os.getenv("POSTPROCESSING_QUEUE", "postprocessing_queue")
POSTPROCESSING_ROUTING_KEY = os.getenv("POSTPROCESSING_ROUTING_KEY", "postprocessing_routing_key")
RETRY_ROUTING_KEY = os.getenv("RETRY_ROUTING_KEY", "preprocessing_retry_routing_key")
MAX_ATTEMPTS = int(os.getenv("CHUNK_MAX_ATTEMPTS", "3"))

# Health check
HEARTBEAT_FILE = os.getenv("HEARTBEAT_FILE", "/tmp/worker.healthy")
HEARTBEAT_INTERVAL_SECONDS = int(os.getenv("HEARTBEAT_INTERVAL_SECONDS", "30"))

# Logging
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")

log = configure_logging(LOG_LEVEL)

_shutdown = threading.Event()
_channel: Optional[BlockingChannel] = None
transcriber: Optional[Transcriber] = None


def get_connection() -> BlockingConnection:
    credentials = pika.PlainCredentials(RABBITMQ_USER, RABBITMQ_PASS)
    params = pika.ConnectionParameters(
        host=RABBITMQ_HOST,
        port=RABBITMQ_PORT,
        credentials=credentials,
        heartbeat=600,
        blocked_connection_timeout=300,
    )
    return pika.BlockingConnection(params)


def publish(channel: BlockingChannel, routing_key: str, payload: Dict[str, Any]) -> None:
    channel.basic_publish(
        exchange=EXCHANGE,
        routing_key=routing_key,
        body=json.dumps(payload).encode("utf-8"),
        properties=BasicProperties(content_type="application/json", delivery_mode=2),
    )


def send_result(channel: BlockingChannel, result: Dict[str, Any]) -> None:
    publish(channel, POSTPROCESSING_ROUTING_KEY, result)


def handle_failure(channel: BlockingChannel, payload: Dict[str, Any], error: Exception) -> bool:
    """Retry through the delay queue, or report a permanent failure.

    Returns True when the message is finished with and should be dead-lettered.
    """
    attempt = int(payload.get("attempt", 0) or 0)
    job_id = payload.get("jobId")
    chunk_index = payload.get("chunkIndex", 0)

    if attempt + 1 < MAX_ATTEMPTS:
        retry_payload = dict(payload)
        retry_payload["attempt"] = attempt + 1
        publish(channel, RETRY_ROUTING_KEY, retry_payload)
        log.warning(
            "Chunk failed, retry %s/%s queued: %s",
            attempt + 1,
            MAX_ATTEMPTS - 1,
            error,
            extra={"jobId": job_id, "chunkIndex": chunk_index, "attempt": attempt + 1},
        )
        return False

    log.error(
        "Chunk failed permanently after %s attempts: %s",
        MAX_ATTEMPTS,
        error,
        extra={"jobId": job_id, "chunkIndex": chunk_index},
    )
    send_result(
        channel,
        {
            "jobId": job_id,
            "chunkIndex": chunk_index,
            "success": False,
            "error": str(error),
        },
    )
    return True


def callback(
    ch: BlockingChannel,
    method: Basic.Deliver,
    properties: BasicProperties,
    body: bytes,
) -> None:
    payload: Dict[str, Any] = {}
    try:
        payload = json.loads(body.decode("utf-8"))
        job_id = payload.get("jobId") or payload.get("job_id")
        file_path = payload.get("filePath") or payload.get("filepath") or payload.get("file_path")
        chunk_index = int(payload.get("chunkIndex", 0) or 0)
        total_chunks = int(payload.get("totalChunks", 1) or 1)
        start_offset = float(payload.get("startOffset", 0.0) or 0.0)
        end_offset = float(payload.get("endOffset", 0.0) or 0.0)
        language = payload.get("language")

        if transcriber is None:
            raise RuntimeError("Transcriber is not loaded")
        if not file_path or not os.path.exists(file_path):
            raise FileNotFoundError(f"Audio file not found: {file_path}")

        result = transcriber.transcribe(
            file_path,
            job_id=str(job_id) if job_id else None,
            chunk_index=chunk_index,
            total_chunks=total_chunks,
            start_offset=start_offset,
            end_offset=end_offset,
            language=language,
        )
        send_result(ch, result)
        ch.basic_ack(delivery_tag=method.delivery_tag)
    except Exception as exc:  # noqa: BLE001 - any failure must produce a result, never a crash
        log.error(
            "Chunk processing failed: %s",
            exc,
            extra={"jobId": payload.get("jobId"), "chunkIndex": payload.get("chunkIndex", 0)},
        )
        try:
            terminal = handle_failure(ch, payload, exc)
        except Exception:
            log.error("Could not publish failure handling: %s", traceback.format_exc())
            terminal = True

        if terminal:
            # Dead-letter the poison message instead of requeueing it forever.
            ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
        else:
            # The retry travels as a fresh message so attempts can be counted.
            ch.basic_ack(delivery_tag=method.delivery_tag)


def _on_signal(signum: int, _frame: Any) -> None:
    log.info("Received signal %s, shutting down after the current chunk", signum)
    _shutdown.set()
    if _channel is not None:
        try:
            _channel.connection.add_callback_threadsafe(_channel.stop_consuming)
        except Exception:  # noqa: BLE001 - best effort, the loop exits anyway
            pass


def _heartbeat_loop() -> None:
    while not _shutdown.is_set():
        try:
            with open(HEARTBEAT_FILE, "w", encoding="utf-8") as handle:
                handle.write(str(int(time.time())))
        except OSError as exc:
            log.warning("Could not write heartbeat file: %s", exc)
        _shutdown.wait(HEARTBEAT_INTERVAL_SECONDS)


def _wait_for_topology(channel: BlockingChannel) -> None:
    """The backend declares the queues; wait politely until they exist."""
    channel.queue_declare(queue=PREPROCESSING_QUEUE, durable=True, passive=True)


def run_once() -> None:
    global _channel
    connection = get_connection()
    try:
        channel = connection.channel()
        _channel = channel
        _wait_for_topology(channel)
        channel.basic_qos(prefetch_count=1)
        channel.basic_consume(queue=PREPROCESSING_QUEUE, on_message_callback=callback)
        log.info("Worker ready, consuming %s", PREPROCESSING_QUEUE)
        channel.start_consuming()
    finally:
        _channel = None
        if connection.is_open:
            connection.close()


def main() -> None:
    global transcriber

    signal.signal(signal.SIGTERM, _on_signal)
    signal.signal(signal.SIGINT, _on_signal)

    threading.Thread(target=_heartbeat_loop, daemon=True).start()

    log.info("Loading Whisper model before consuming")
    transcriber = Transcriber()

    backoff = 1
    while not _shutdown.is_set():
        try:
            run_once()
            backoff = 1
        except (AMQPConnectionError, ConnectionClosedByBroker, ChannelClosedByBroker) as exc:
            log.warning("RabbitMQ not ready (%s), retrying in %ss", exc, backoff)
            time.sleep(backoff)
            backoff = min(backoff * 2, 30)
        except Exception as exc:  # noqa: BLE001 - never let the worker die silently
            log.error("Unexpected consumer error: %s", exc, exc_info=True)
            time.sleep(backoff)
            backoff = min(backoff * 2, 30)

    log.info("Worker stopped")
    try:
        os.remove(HEARTBEAT_FILE)
    except OSError:
        pass


if __name__ == "__main__":
    sys.exit(main())
