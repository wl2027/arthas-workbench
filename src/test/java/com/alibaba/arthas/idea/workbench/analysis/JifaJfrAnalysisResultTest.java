package com.alibaba.arthas.idea.workbench.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import org.eclipse.jifa.jfr.model.AnalysisResult;
import org.eclipse.jifa.jfr.model.DimensionResult;
import org.eclipse.jifa.jfr.model.PerfDimension;
import org.eclipse.jifa.jfr.model.Task;
import org.eclipse.jifa.jfr.model.TaskCPUTime;
import org.eclipse.jifa.jfr.model.TaskCount;
import org.eclipse.jifa.jfr.vo.Metadata;
import org.junit.Test;

/**
 * JFR 结果封装的视图选择逻辑测试。
 */
public class JifaJfrAnalysisResultTest {

    @Test
    public void shouldPreferFirstDimensionThatActuallyHasData() {
        Metadata metadata = new Metadata();
        metadata.setPerfDimensions(new PerfDimension[] {
            PerfDimension.of("CPU Time", "CPU Time", new org.eclipse.jifa.jfr.model.Filter[0]),
            PerfDimension.of("CPU Sample", "CPU Sample", new org.eclipse.jifa.jfr.model.Filter[0]),
        });

        AnalysisResult analysisResult = new AnalysisResult();
        analysisResult.setCpuTime(new DimensionResult<>());

        DimensionResult<TaskCount> cpuSamples = new DimensionResult<>();
        TaskCount taskCount = new TaskCount(new Task());
        taskCount.getTask().setName("worker-1");
        cpuSamples.add(taskCount);
        analysisResult.setCpuSample(cpuSamples);

        JifaJfrAnalysisResult result = new JifaJfrAnalysisResult(Path.of("sample.jfr"), null, metadata, analysisResult);

        assertEquals("CPU Sample", result.preferredDimension().getKey());
    }

    @Test
    public void shouldExposeDimensionTaskMetrics() {
        Metadata metadata = new Metadata();
        metadata.setPerfDimensions(new PerfDimension[] {
            PerfDimension.of("CPU Time", "CPU Time", new org.eclipse.jifa.jfr.model.Filter[0]),
        });

        AnalysisResult analysisResult = new AnalysisResult();
        DimensionResult<TaskCPUTime> cpuTime = new DimensionResult<>();
        TaskCPUTime task = new TaskCPUTime(new Task());
        task.getTask().setName("worker-2");
        task.setUser(80L);
        task.setSystem(20L);
        cpuTime.add(task);
        analysisResult.setCpuTime(cpuTime);

        JifaJfrAnalysisResult result = new JifaJfrAnalysisResult(Path.of("sample.jfr"), null, metadata, analysisResult);

        assertEquals(1, result.taskMetrics(metadata.getPerfDimensions()[0]).size());
        assertEquals(100L, result.totalWeight(metadata.getPerfDimensions()[0]));
        assertEquals("worker-2", result.hottestTaskName(metadata.getPerfDimensions()[0]));
        assertTrue(result.taskMetrics(metadata.getPerfDimensions()[0])
                .get(0)
                .detail()
                .contains("user=80"));
        assertEquals(1, result.dimensionOverview().size());
        assertEquals("CPU Time", result.dimensionOverview().get(0).dimensionKey());
        assertEquals(100L, result.dimensionOverview().get(0).totalWeight());
    }

    @Test
    public void shouldPreferCpuTimeToMatchWebDefaultWhenItHasData() {
        Metadata metadata = new Metadata();
        metadata.setPerfDimensions(new PerfDimension[] {
            PerfDimension.of("CPU Time", "CPU Time", new org.eclipse.jifa.jfr.model.Filter[0]),
            PerfDimension.of("CPU Sample", "CPU Sample", new org.eclipse.jifa.jfr.model.Filter[0]),
        });

        AnalysisResult analysisResult = new AnalysisResult();
        DimensionResult<TaskCPUTime> cpuTime = new DimensionResult<>();
        TaskCPUTime cpuTask = new TaskCPUTime(new Task());
        cpuTask.getTask().setName("main");
        cpuTask.setUser(10L);
        cpuTask.setSystem(20L);
        cpuTime.add(cpuTask);
        analysisResult.setCpuTime(cpuTime);

        DimensionResult<TaskCount> cpuSamples = new DimensionResult<>();
        TaskCount sampleTask = new TaskCount(new Task());
        sampleTask.getTask().setName("main");
        cpuSamples.add(sampleTask);
        analysisResult.setCpuSample(cpuSamples);

        JifaJfrAnalysisResult result = new JifaJfrAnalysisResult(Path.of("sample.jfr"), null, metadata, analysisResult);

        assertEquals("CPU Time", result.preferredDimension().getKey());
    }
}
