package com.praful.filehandler.upload;

import jakarta.persistence.*;
import lombok.Data;

import java.util.UUID;

@Data
@Entity
@Table(name = "chunk_results")
public class ChunkResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID jobId;
    private Integer chunkIndex;
    private Integer totalChunks;
    private Double startOffset;
    private Double endOffset;

    @Column(columnDefinition = "text")
    private String text;

    @Column(columnDefinition = "text")
    private String segmentsJson;

    private String language;
    private Boolean success;
    private String error;
}
