package com.praful.filehandler.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Audio files are the expensive part of this system, so they do not live forever.
 * Finished and failed jobs older than the retention window are removed, along with
 * their chunk results.
 */
@Component
public class StorageCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupScheduler.class);

    private final UploadRepository uploadRepository;
    private final ChunkResultRepository chunkResultRepository;
    private final int retentionDays;

    public StorageCleanupScheduler(UploadRepository uploadRepository,
                                   ChunkResultRepository chunkResultRepository,
                                   @Value("${app.storage.retention-days:7}") int retentionDays) {
        this.uploadRepository = uploadRepository;
        this.chunkResultRepository = chunkResultRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${app.cleanup.cron:0 0 3 * * *}")
    public void cleanup() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<Upload> expired = uploadRepository.findByCreatedAtBefore(cutoff);
        for (Upload job : expired) {
            try {
                deleteFiles(job);
                chunkResultRepository.deleteByJobId(job.getJobId());
                uploadRepository.delete(job);
                log.info("Cleaned up job {} (older than {} days)", job.getJobId(), retentionDays);
            } catch (Exception e) {
                log.warn("Could not clean up job {}: {}", job.getJobId(), e.getMessage());
            }
        }
    }

    private void deleteFiles(Upload job) {
        if (job.getFilepath() != null) {
            deleteQuietly(Path.of(job.getFilepath()));
        }
        if (job.getChunkDir() != null) {
            Path dir = Path.of(job.getChunkDir());
            if (Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(StorageCleanupScheduler::deleteQuietly);
                } catch (IOException e) {
                    log.warn("Could not walk chunk dir {}: {}", dir, e.getMessage());
                }
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Best effort: a locked file is retried on the next run.
        }
    }
}
