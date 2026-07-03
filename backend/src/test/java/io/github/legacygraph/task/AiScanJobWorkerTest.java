package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.entity.AiScanJob;
import io.github.legacygraph.repository.AiScanJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiScanJobWorkerTest {

    @Test
    void processPendingJobs_runsOldOrchestrationAndMarksSuccess() throws Exception {
        AiScanJobRepository aiScanJobRepository = mock(AiScanJobRepository.class);
        AiScanOrchestrator aiScanOrchestrator = mock(AiScanOrchestrator.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AiScanJobWorker worker = new AiScanJobWorker(aiScanJobRepository, aiScanOrchestrator, objectMapper);

        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);

        AiScanJob job = new AiScanJob();
        job.setId("job-1");
        job.setProjectId("project-1");
        job.setVersionId("version-1");
        job.setStatus("PENDING");
        job.setConfigJson(objectMapper.writeValueAsString(config));
        job.setCreatedAt(LocalDateTime.now().minusMinutes(1));

        when(aiScanJobRepository.selectList(any())).thenReturn(List.of(job));
        List<String> statusUpdates = new ArrayList<>();
        List<LocalDateTime> finishedAtUpdates = new ArrayList<>();
        doAnswer(invocation -> {
            AiScanJob updated = invocation.getArgument(0);
            statusUpdates.add(updated.getStatus());
            finishedAtUpdates.add(updated.getFinishedAt());
            return 1;
        }).when(aiScanJobRepository).updateById(any(AiScanJob.class));

        worker.processPendingJobs();

        verify(aiScanJobRepository, org.mockito.Mockito.times(2)).updateById(any(AiScanJob.class));
        assertEquals(List.of("RUNNING", "SUCCESS"), statusUpdates);
        assertNotNull(finishedAtUpdates.get(1));
        verify(aiScanOrchestrator).orchestrate(
                org.mockito.Mockito.eq("project-1"),
                org.mockito.Mockito.eq("version-1"),
                org.mockito.Mockito.argThat(AiScanConfig::isEnableAi),
                any());
    }
}
