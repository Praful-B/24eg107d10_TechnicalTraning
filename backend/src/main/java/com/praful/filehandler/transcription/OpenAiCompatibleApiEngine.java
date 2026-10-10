package com.praful.filehandler.transcription;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.praful.filehandler.upload.TranscriptSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Fallback transcription through an OpenAI-compatible audio API (works with
 * Groq's free tier out of the box). Runs no model locally, so it uses almost no
 * container memory - useful when the embedded whisper.cpp path is too heavy for
 * a 512 MB Render instance.
 */
@Component
public class OpenAiCompatibleApiEngine implements TranscriptionEngine {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String url;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleApiEngine(ObjectMapper objectMapper,
                                     @Value("${app.transcription.api.url:https://api.groq.com/openai/v1/audio/transcriptions}") String url,
                                     @Value("${app.transcription.api.key:}") String apiKey,
                                     @Value("${app.transcription.api.model:whisper-large-v3-turbo}") String model) {
        this.restClient = RestClient.create();
        this.objectMapper = objectMapper;
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
    }

    /** True when an API key is present, so the fallback can safely be attempted. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String name() {
        return "openai-compatible-api";
    }

    @Override
    public EngineResult transcribe(Path audio, String language) throws IOException {
        if (!isConfigured()) {
            throw new IllegalStateException("Transcription API key is not configured (set GROQ_API_KEY)");
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(audio));
        body.add("model", model);
        body.add("response_format", "verbose_json");
        if (language != null && !language.isBlank()) {
            body.add("language", language);
        }

        String response;
        try {
            response = restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new IOException("Transcription API error " + e.getStatusCode() + ": "
                    + e.getResponseBodyAsString(), e);
        }

        if (response == null || response.isBlank()) {
            throw new IOException("Empty transcription API response");
        }
        return parse(response);
    }

    private EngineResult parse(String json) throws IOException {
        ApiResponse parsed = objectMapper.readValue(json, ApiResponse.class);

        List<TranscriptSegment> segments = new ArrayList<>();
        if (parsed.segments() != null) {
            for (ApiSegment segment : parsed.segments()) {
                if (segment == null) continue;
                String text = segment.text() == null ? "" : segment.text().trim();
                segments.add(new TranscriptSegment(segment.start(), segment.end(), text));
            }
        }

        String text = parsed.text() != null && !parsed.text().isBlank()
                ? parsed.text().trim()
                : join(segments);
        return new EngineResult(segments, text, parsed.language());
    }

    private static String join(List<TranscriptSegment> segments) {
        StringBuilder builder = new StringBuilder();
        for (TranscriptSegment segment : segments) {
            if (segment.text() == null || segment.text().isBlank()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(segment.text().trim());
        }
        return builder.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiResponse(String text, String language, List<ApiSegment> segments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiSegment(Double start, Double end, String text) {
    }
}
