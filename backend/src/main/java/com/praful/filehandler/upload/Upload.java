package com.praful.filehandler.upload;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
public class Upload {
    @Id
    UUID jobId;

    /** Owner of the job, used to scope access to the authenticated user. */
    private UUID userId;

    @Enumerated(EnumType.STRING)
    MessageStatus status;

    private String originalFileName;
    private String filepath;

    /** Directory holding the normalized audio and its chunk files, for cleanup. */
    private String chunkDir;

    /** Assembled transcript text once every chunk has been received. */
    @Column(columnDefinition = "text")
    private String content;

    /** Assembled list of timestamped segments (JSON), used by the frontend viewer. */
    @Column(columnDefinition = "text")
    private String segmentsJson;

    private String language;
    private Integer totalChunks;
    private Integer chunksReceived;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String failureReason;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = MessageStatus.SAVED;
        }
        if (chunksReceived == null) {
            chunksReceived = 0;
        }
        if (totalChunks == null) {
            totalChunks = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
