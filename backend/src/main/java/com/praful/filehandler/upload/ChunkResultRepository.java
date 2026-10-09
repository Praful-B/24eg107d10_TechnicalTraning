package com.praful.filehandler.upload;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChunkResultRepository extends JpaRepository<ChunkResult, Long> {
    List<ChunkResult> findByJobId(UUID jobId);

    Optional<ChunkResult> findByJobIdAndChunkIndex(UUID jobId, Integer chunkIndex);

    void deleteByJobId(UUID jobId);
}
