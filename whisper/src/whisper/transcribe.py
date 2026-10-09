from __future__ import annotations

import logging
import os
import time
from typing import Any, Dict, List, Optional

from faster_whisper import WhisperModel

log = logging.getLogger(__name__)

# Configuration from environment
MODEL_SIZE = os.getenv("WHISPER_MODEL_SIZE", "base")
DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "int8")
BEAM_SIZE = int(os.getenv("WHISPER_BEAM_SIZE", "5"))
VAD_FILTER = os.getenv("WHISPER_VAD_FILTER", "true").lower() not in ("0", "false", "no")
WORD_TIMESTAMPS = os.getenv("WHISPER_WORD_TIMESTAMPS", "false").lower() in ("1", "true", "yes")
TRANSLATE = os.getenv("WHISPER_TRANSLATE", "false").lower() in ("1", "true", "yes")


def _parse_bool(value: Optional[str], default: bool = False) -> bool:
    """Parse a boolean from environment variable value."""
    if value is None:
        return default
    return value.lower() in ("1", "true", "yes")


class Transcriber:
    """Whisper model wrapper for transcribing audio chunks.

    The model is loaded once at startup and reused for all chunks.
    """

    def __init__(self) -> None:
        log.info(
            "Loading Whisper model: size=%s, device=%s, compute_type=%s",
            MODEL_SIZE,
            DEVICE,
            COMPUTE_TYPE,
        )
        self.model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)

    def transcribe(
        self,
        audio_path: str,
        *,
        job_id: Optional[str] = None,
        chunk_index: int = 0,
        total_chunks: int = 1,
        start_offset: float = 0.0,
        end_offset: float = 0.0,
        language: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Transcribe an audio chunk and return the result.

        Args:
            audio_path: Path to the audio file to transcribe
            job_id: ID of the parent job (for logging)
            chunk_index: Index of this chunk within the job
            total_chunks: Total number of chunks in the job
            start_offset: Start time of this chunk in the original audio (seconds)
            end_offset: End time of this chunk in the original audio (seconds)
            language: Optional language code to hint Whisper

        Returns:
            Dictionary with segments, text, language, and metrics
        """
        task = "translate" if TRANSLATE else "transcribe"
        started = time.monotonic()

        segments, info = self.model.transcribe(
            audio_path,
            beam_size=BEAM_SIZE,
            language=language,
            task=task,
            vad_filter=VAD_FILTER,
            word_timestamps=WORD_TIMESTAMPS,
        )

        # Process segments with offset adjustment
        segment_list: List[Dict[str, Any]] = []
        text_parts: List[str] = []

        for segment in segments:
            text = (segment.text or "").strip()
            item: Dict[str, Any] = {
                "start": round(float(segment.start) + start_offset, 3),
                "end": round(float(segment.end) + start_offset, 3),
                "text": text,
            }
            # Optional confidence score
            confidence = getattr(segment, "avg_logprob", None)
            if confidence is not None:
                item["confidence"] = round(float(confidence), 4)

            segment_list.append(item)
            if text:
                text_parts.append(text)

        # Calculate metrics
        inference_time = time.monotonic() - started
        audio_duration = self._get_audio_duration(info, start_offset, end_offset)
        metrics = self._calculate_metrics(audio_duration, inference_time)

        log.info(
            "Transcribed chunk %d/%d in %.2fs (rtf=%.3f)",
            chunk_index + 1,
            total_chunks,
            inference_time,
            metrics["realTimeFactor"] or 0,
            extra={
                "jobId": job_id,
                "chunkIndex": chunk_index,
                "metrics": metrics,
            },
        )

        return {
            "jobId": str(job_id) if job_id else None,
            "chunkIndex": chunk_index,
            "totalChunks": total_chunks,
            "startOffset": start_offset,
            "endOffset": end_offset,
            "language": getattr(info, "language", language),
            "duration": round(audio_duration, 3),
            "segments": segment_list,
            "text": " ".join(text_parts).strip(),
            "metrics": metrics,
            "success": True,
        }

    def _get_audio_duration(
        self,
        info: Any,
        start_offset: float,
        end_offset: float,
    ) -> float:
        """Get audio duration from Whisper info or calculate from offsets."""
        duration = getattr(info, "duration", None)
        if duration is not None:
            return float(duration)
        calculated = end_offset - start_offset
        return calculated if calculated > 0 else 0.0

    def _calculate_metrics(
        self,
        audio_duration: float,
        inference_time: float,
    ) -> Dict[str, Optional[float]]:
        """Calculate performance metrics for the transcription."""
        return {
            "audioDurationSeconds": round(audio_duration, 3),
            "inferenceTimeSeconds": round(inference_time, 3),
            "realTimeFactor": (
                round(inference_time / audio_duration, 3)
                if audio_duration > 0
                else None
            ),
        }
