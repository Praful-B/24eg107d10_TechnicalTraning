package com.praful.filehandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wave Parser - Asynchronous audio transcription service.
 *
 * Upload audio → chunk on silences → transcribe with Whisper → assemble results.
 * The frontend polls job status and receives live updates over SSE.
 */
@SpringBootApplication
@EnableScheduling
public class FilehandlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FilehandlerApplication.class, args);
    }
}
