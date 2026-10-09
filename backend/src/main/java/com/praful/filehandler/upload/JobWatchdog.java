package com.praful.filehandler.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Flags jobs that never finish, so a lost worker cannot leave jobs stuck forever. */
@Component
public class JobWatchdog {

    private static final Logger log = LoggerFactory.getLogger(JobWatchdog.class);

    private final UploadRepository uploadRepository;
    private final JobEventService jobEventService;
    private final long timeoutMinutes;

    public JobWatchdog(UploadRepository uploadRepository,
                       JobEventService jobEventService,
                       @Value("${app.job.timeout-minutes:30}") long timeoutMinutes) {
        this.uploadRepository = uploadRepository;
        this.jobEventService = jobEventService;
        this.timeoutMinutes = timeoutMinutes;
    }

    @Scheduled(fixedDelayString = "${app.job.watchdog-interval-ms:60000}")
    @Transactional
    public void failStuckJobs() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<Upload> stuck = uploadRepository.findByStatusAndUpdatedAtBefore(MessageStatus.PROCESSING, cutoff);
        for (Upload job : stuck) {
            job.setStatus(MessageStatus.FAILED);
            job.setFailureReason("Timed out after " + timeoutMinutes + " minutes");
            uploadRepository.save(job);
            jobEventService.publish(JobEvent.from(job));
            log.warn("Marked stuck job {} as FAILED", job.getJobId());
        }
    }
}
