package com.praful.filehandler.upload;

/** One search hit: the matching segment plus its global timestamps. */
public record TranscriptMatch(Double start, Double end, String text) {
}
