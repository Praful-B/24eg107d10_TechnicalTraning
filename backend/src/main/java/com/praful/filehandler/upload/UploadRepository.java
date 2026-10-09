package com.praful.filehandler.upload;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadRepository extends JpaRepository<Upload, UUID> {

    List<Upload> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Upload> findByStatusAndUpdatedAtBefore(MessageStatus status, LocalDateTime cutoff);

    long countByUserIdAndStatusIn(UUID userId, Collection<MessageStatus> statuses);

    List<Upload> findByCreatedAtBefore(LocalDateTime cutoff);

    /**
     * Fetch a job while taking a row-level write lock so that two results arriving
     * at the same time cannot both see the "all chunks received" state and each
     * trigger transcript assembly.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Upload u where u.jobId = :jobId")
    Optional<Upload> findWithLockingByJobId(@Param("jobId") UUID jobId);
}
