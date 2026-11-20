package com.exe.morphia3d.design;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.design.worker", name = "enabled", havingValue = "true")
public class DesignPipelineWorkerScheduler {
    private final DesignPipelineWorker worker;
    public DesignPipelineWorkerScheduler(DesignPipelineWorker worker) { this.worker = worker; }

    @Scheduled(fixedDelayString = "${app.design.worker.poll-interval-ms:5000}")
    public void poll() { worker.processOne(); }
}
