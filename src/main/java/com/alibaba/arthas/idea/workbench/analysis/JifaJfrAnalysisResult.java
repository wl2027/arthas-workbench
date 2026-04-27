package com.alibaba.arthas.idea.workbench.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.eclipse.jifa.analysis.listener.ProgressListener;
import org.eclipse.jifa.jfr.JFRAnalyzerImpl;
import org.eclipse.jifa.jfr.common.ProfileDimension;
import org.eclipse.jifa.jfr.enums.Unit;
import org.eclipse.jifa.jfr.model.AnalysisResult;
import org.eclipse.jifa.jfr.model.DimensionResult;
import org.eclipse.jifa.jfr.model.PerfDimension;
import org.eclipse.jifa.jfr.model.TaskAllocatedMemory;
import org.eclipse.jifa.jfr.model.TaskAllocations;
import org.eclipse.jifa.jfr.model.TaskCPUTime;
import org.eclipse.jifa.jfr.model.TaskCount;
import org.eclipse.jifa.jfr.model.TaskResultBase;
import org.eclipse.jifa.jfr.model.TaskSum;
import org.eclipse.jifa.jfr.vo.FlameGraph;
import org.eclipse.jifa.jfr.vo.Metadata;

/**
 * JFR 分析结果。
 */
public final class JifaJfrAnalysisResult implements JifaAnalysisResult {

    private final Path path;
    private final Metadata metadata;
    private final AnalysisResult result;
    private final Supplier<JFRAnalyzerImpl> analyzerSupplier;
    private final JfrAnalysisCache cache = JfrAnalysisCache.defaultCache();
    private final Map<String, FlameGraph> flameGraphs = new ConcurrentHashMap<>();
    private final Map<String, List<TaskMetricRow>> taskMetrics = new ConcurrentHashMap<>();

    private volatile JFRAnalyzerImpl analyzer;

    public JifaJfrAnalysisResult(Path path, JFRAnalyzerImpl analyzer, Metadata metadata, AnalysisResult result) {
        this(
                path,
                analyzer,
                metadata,
                result,
                analyzer != null
                        ? () -> analyzer
                        : () -> new JFRAnalyzerImpl(path, Map.of(), ProgressListener.NoOpProgressListener));
    }

    public JifaJfrAnalysisResult(Path path, Metadata metadata, AnalysisResult result) {
        this(
                path,
                null,
                metadata,
                result,
                () -> new JFRAnalyzerImpl(path, Map.of(), ProgressListener.NoOpProgressListener));
    }

    private JifaJfrAnalysisResult(
            Path path,
            JFRAnalyzerImpl analyzer,
            Metadata metadata,
            AnalysisResult result,
            Supplier<JFRAnalyzerImpl> analyzerSupplier) {
        this.path = path;
        this.analyzer = analyzer;
        this.metadata = metadata;
        this.result = result;
        this.analyzerSupplier = analyzerSupplier;
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public JifaArtifactType getType() {
        return JifaArtifactType.JFR;
    }

    public JFRAnalyzerImpl getAnalyzer() {
        JFRAnalyzerImpl current = analyzer;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (analyzer == null) {
                analyzer = analyzerSupplier.get();
            }
            return analyzer;
        }
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public AnalysisResult getResult() {
        return result;
    }

    public PerfDimension preferredDimension() {
        PerfDimension[] dimensions = metadata.getPerfDimensions();
        if (dimensions == null || dimensions.length == 0) {
            return null;
        }
        for (PerfDimension dimension : dimensions) {
            if (ProfileDimension.CPU.getKey().equals(dimension.getKey()) && taskCount(dimension) > 0) {
                return dimension;
            }
        }
        for (PerfDimension dimension : dimensions) {
            if (taskCount(dimension) > 0) {
                return dimension;
            }
        }
        return dimensions[0];
    }

    public int taskCount(PerfDimension dimension) {
        if (dimension == null) {
            return 0;
        }
        DimensionResult<?> dimensionResult = dimensionResult(ProfileDimension.of(dimension.getKey()));
        return dimensionResult == null || dimensionResult.getList() == null
                ? 0
                : dimensionResult.getList().size();
    }

    public long totalWeight(PerfDimension dimension) {
        return taskMetrics(dimension).stream().mapToLong(TaskMetricRow::value).sum();
    }

    public String hottestTaskName(PerfDimension dimension) {
        return taskMetrics(dimension).stream()
                .findFirst()
                .map(TaskMetricRow::taskName)
                .orElse("-");
    }

    public List<TaskMetricRow> taskMetrics(PerfDimension dimension) {
        if (dimension == null) {
            return List.of();
        }
        return taskMetrics.computeIfAbsent(dimension.getKey(), ignored -> buildTaskMetrics(dimension));
    }

    public List<DimensionOverviewRow> dimensionOverview() {
        PerfDimension[] dimensions = metadata.getPerfDimensions();
        if (dimensions == null || dimensions.length == 0) {
            return List.of();
        }
        List<DimensionOverviewRow> rows = new ArrayList<>(dimensions.length);
        for (PerfDimension dimension : dimensions) {
            rows.add(new DimensionOverviewRow(
                    dimension.getKey(),
                    dimension.getUnit() == null ? Unit.COUNT : dimension.getUnit(),
                    taskCount(dimension),
                    totalWeight(dimension),
                    hottestTaskName(dimension)));
        }
        rows.sort((left, right) -> Long.compare(right.totalWeight, left.totalWeight));
        return rows;
    }

    public FlameGraph flameGraph(PerfDimension dimension) {
        if (dimension == null) {
            return null;
        }
        return flameGraphs.computeIfAbsent(dimension.getKey(), ignored -> loadOrBuildFlameGraph(dimension));
    }

    private DimensionResult<?> dimensionResult(ProfileDimension dimension) {
        return switch (dimension) {
            case CPU -> result.getCpuTime();
            case CPU_SAMPLE -> result.getCpuSample();
            case WALL_CLOCK -> result.getWallClock();
            case NATIVE_EXECUTION_SAMPLES -> result.getNativeExecutionSamples();
            case ALLOC -> result.getAllocations();
            case MEM -> result.getAllocatedMemory();
            case FILE_IO_TIME -> result.getFileIOTime();
            case FILE_READ_SIZE -> result.getFileReadSize();
            case FILE_WRITE_SIZE -> result.getFileWriteSize();
            case SOCKET_READ_TIME -> result.getSocketReadTime();
            case SOCKET_READ_SIZE -> result.getSocketReadSize();
            case SOCKET_WRITE_TIME -> result.getSocketWriteTime();
            case SOCKET_WRITE_SIZE -> result.getSocketWriteSize();
            case SYNCHRONIZATION -> result.getSynchronization();
            case THREAD_PARK -> result.getThreadPark();
            case CLASS_LOAD_COUNT -> result.getClassLoadCount();
            case CLASS_LOAD_WALL_TIME -> result.getClassLoadWallTime();
            case THREAD_SLEEP -> result.getThreadSleepTime();
            case PROBLEMS -> null;
        };
    }

    private FlameGraph loadOrBuildFlameGraph(PerfDimension dimension) {
        try {
            FlameGraph cachedFlameGraph = cache.loadFlameGraph(path, dimension.getKey());
            if (cachedFlameGraph != null) {
                return cachedFlameGraph;
            }
        } catch (Exception ignored) {
            // 缓存读取失败不应阻塞 JFR 分析。
        }

        FlameGraph flameGraph = getAnalyzer().getFlameGraph(dimension.getKey(), false, List.of());
        try {
            cache.storeFlameGraph(path, dimension.getKey(), flameGraph);
        } catch (Exception ignored) {
            // 缓存写入失败不影响界面展示。
        }
        return flameGraph;
    }

    private List<TaskMetricRow> buildTaskMetrics(PerfDimension dimension) {
        DimensionResult<?> dimensionResult = dimensionResult(ProfileDimension.of(dimension.getKey()));
        if (dimensionResult == null || dimensionResult.getList() == null) {
            return List.of();
        }

        List<TaskMetricRow> rows = new ArrayList<>();
        for (Object item : dimensionResult.getList()) {
            if (!(item instanceof TaskResultBase taskResult) || taskResult.getTask() == null) {
                continue;
            }
            rows.add(toTaskMetricRow(taskResult, dimension));
        }
        rows.sort((left, right) -> Long.compare(right.value, left.value));
        return rows;
    }

    private TaskMetricRow toTaskMetricRow(TaskResultBase taskResult, PerfDimension dimension) {
        long primaryValue = primaryValue(taskResult);
        long sampleCount = taskResult.getSamples() == null
                ? 0L
                : taskResult.getSamples().values().stream()
                        .mapToLong(Long::longValue)
                        .sum();
        String detail =
                switch (taskResult) {
                    case TaskCPUTime cpuTime -> "user=" + cpuTime.getUser() + ", system=" + cpuTime.getSystem();
                    case TaskAllocatedMemory allocatedMemory -> "allocated=" + allocatedMemory.getAllocatedMemory();
                    case TaskAllocations allocations -> "allocations=" + allocations.getAllocations();
                    case TaskCount count -> "count=" + count.getCount();
                    case TaskSum sum -> "sum=" + sum.getSum();
                    default -> dimension.getKey();
                };
        return new TaskMetricRow(
                taskResult.getTask().getName(),
                primaryValue,
                sampleCount,
                detail,
                taskResult.getTask().getStart(),
                taskResult.getTask().getEnd());
    }

    private long primaryValue(TaskResultBase taskResult) {
        return switch (taskResult) {
            case TaskCPUTime cpuTime -> cpuTime.totalCPUTime();
            case TaskAllocatedMemory allocatedMemory -> allocatedMemory.getAllocatedMemory();
            case TaskAllocations allocations -> allocations.getAllocations();
            case TaskCount count -> count.getCount();
            case TaskSum sum -> sum.getSum();
            default -> 0L;
        };
    }

    public record TaskMetricRow(
            String taskName, long value, long sampleCount, String detail, long startMillis, long endMillis) {}

    public record DimensionOverviewRow(
            String dimensionKey, Unit unit, int taskCount, long totalWeight, String hottestTask) {}
}
