package io.github.legacygraph.graphify;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GraphifyImportJobSchedulerTest {

    @Test
    void processQueuedJobsExecutesQueuedJobs() {
        GraphifyImportJobRepository repository = new GraphifyImportJobRepository();
        GraphifyImportJobService jobService = mock(GraphifyImportJobService.class);
        GraphifyImportJobScheduler scheduler = new GraphifyImportJobScheduler(repository, jobService);
        GraphifyImportJob job = GraphifyImportJob.builder()
                .jobId("job-1")
                .projectId("project-1")
                .versionId("version-1")
                .status(GraphifyImportJob.Status.QUEUED)
                .build();
        repository.save(job);

        scheduler.processQueuedJobs();

        verify(jobService).execute(job);
    }
}
