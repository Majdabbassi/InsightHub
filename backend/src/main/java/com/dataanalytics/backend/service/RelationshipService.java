package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.AnalyticsColumnStats;
import com.dataanalytics.backend.dto.CreateRelationshipRequest;
import com.dataanalytics.backend.dto.RelationshipResponse;
import com.dataanalytics.backend.dto.ScanResponse;
import com.dataanalytics.backend.exception.InvalidRelationshipException;
import com.dataanalytics.backend.exception.RelationshipNotFoundException;
import com.dataanalytics.backend.event.RelationshipScanRequestedEvent;
import com.dataanalytics.backend.model.AnalysisResult;
import com.dataanalytics.backend.model.Dataset;
import com.dataanalytics.backend.model.DatasetRelationship;
import com.dataanalytics.backend.model.DatasetRelationship.RelationshipStatus;
import com.dataanalytics.backend.model.Project;
import com.dataanalytics.backend.repository.AnalysisResultRepository;
import com.dataanalytics.backend.repository.DatasetRelationshipRepository;
import com.dataanalytics.backend.repository.DatasetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Relationship detection between a project's datasets plus CRUD for the
 * resulting suggestions. FOREIGN_KEY detection compares IDENTIFIER-classified
 * columns with matching names, measures how much of the child column's values
 * appear in the parent's, and stores a SUGGESTED relationship when the match
 * reaches MIN_MATCH_PERCENTAGE. SIBLING detection flags pairs whose schemas
 * overlap by at least SIBLING_MIN_OVERLAP_PERCENT (schema twins, e.g. two
 * months of the same report) — but never for pairs that already have a
 * foreign-key-style link.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RelationshipService {

    /** Suggestions below this containment share are treated as noise. */
    private static final double MIN_MATCH_PERCENTAGE = 50.0;

    /**
     * Minimum case-insensitive column-name overlap (shared / max of the two
     * column counts, in percent) for a pair to count as siblings.
     */
    private static final double SIBLING_MIN_OVERLAP_PERCENT = 70.0;

    /** Tolerance below which two uniqueness ratios count as equally key-like. */
    private static final double UNIQUENESS_EPSILON = 0.02;

    /** Safety cap on distinct values collected per column during matching. */
    private static final int MAX_VALUES_PER_COLUMN = 200_000;

    private final DatasetRelationshipRepository relationshipRepository;
    private final DatasetRepository datasetRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final ProjectService projectService;
    private final FileStorageService fileStorageService;
    private final ProjectContextCache projectContextCache;
    private final ObjectMapper objectMapper;

    // ===== Queries / CRUD =====

    @Transactional(readOnly = true)
    public List<RelationshipResponse> list(String ownerEmail, Long projectId) {
        Project project = projectService.findOwnedProject(ownerEmail, projectId);
        return relationshipRepository.findByProjectIdOrderByCreatedAtDesc(project.getId())
                .stream().map(this::toResponse).toList();
    }

    /** Ownership-checked entity lookup shared with the insights features. */
    @Transactional(readOnly = true)
    public DatasetRelationship findOwnedRelationship(
            String ownerEmail, Long projectId, Long relationshipId) {
        Project project = projectService.findOwnedProject(ownerEmail, projectId);
        DatasetRelationship relationship = relationshipRepository.findById(relationshipId)
                .orElseThrow(() -> new RelationshipNotFoundException(relationshipId));
        if (!relationship.getProject().getId().equals(project.getId())) {
            throw new RelationshipNotFoundException(relationshipId);
        }
        return relationship;
    }

    @Transactional
    public RelationshipResponse createManual(
            String ownerEmail, Long projectId, CreateRelationshipRequest request) {
        Project project = projectService.findOwnedProject(ownerEmail, projectId);
        Dataset datasetA = findProjectDataset(projectId, request.datasetAId(), "datasetAId");
        Dataset datasetB = findProjectDataset(projectId, request.datasetBId(), "datasetBId");
        if (datasetA.getId().equals(datasetB.getId())) {
            throw new InvalidRelationshipException(
                    "A relationship needs two different datasets.");
        }
        DatasetRelationship relationship = DatasetRelationship.builder()
                .project(project)
                .datasetA(datasetA)
                .datasetB(datasetB)
                .sharedColumnA(request.sharedColumnA().trim())
                .sharedColumnB(request.sharedColumnB().trim())
                .matchPercentage(null)
                .status(RelationshipStatus.MANUAL)
                .relationshipType(DatasetRelationship.RelationshipType.FOREIGN_KEY)
                .build();
        RelationshipResponse response = toResponse(relationshipRepository.save(relationship));
        projectContextCache.invalidate(projectId);
        return response;
    }

    @Transactional
    public RelationshipResponse confirm(String ownerEmail, Long projectId, Long relationshipId) {
        DatasetRelationship relationship = findOwnedRelationship(ownerEmail, projectId, relationshipId);
        relationship.setStatus(RelationshipStatus.CONFIRMED);
        RelationshipResponse response = toResponse(relationship);
        projectContextCache.invalidate(projectId);
        return response;
    }

    /**
     * Rejected relationships stay in the database so future scans recognise
     * the rejected pair and do not recreate the suggestion.
     */
    @Transactional
    public RelationshipResponse reject(String ownerEmail, Long projectId, Long relationshipId) {
        DatasetRelationship relationship = findOwnedRelationship(ownerEmail, projectId, relationshipId);
        relationship.setStatus(RelationshipStatus.REJECTED);
        RelationshipResponse response = toResponse(relationship);
        projectContextCache.invalidate(projectId);
        return response;
    }

    /** Hard delete; works for any status. A later scan may re-suggest the pair. */
    @Transactional
    public void delete(String ownerEmail, Long projectId, Long relationshipId) {
        relationshipRepository.delete(
                findOwnedRelationship(ownerEmail, projectId, relationshipId));
        projectContextCache.invalidate(projectId);
    }

    // ===== Detection =====

    /** Full pairwise scan across every analysed dataset of the project. */
    @Transactional
    public ScanResponse scan(String ownerEmail, Long projectId) {
        Project project = projectService.findOwnedProject(ownerEmail, projectId);
        return scanProject(project, null);
    }

    /**
     * Runs after the analysis transaction commits (see AnalysisService), off
     * the request thread. Only compares the new dataset against the rest.
     * Never throws - detection problems must not break the analysis flow
     * that triggered it.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRelationshipScanRequested(RelationshipScanRequestedEvent event) {
        detectForNewDataset(event.projectId(), event.datasetId());
    }

    /** Scoped follow-up used by the post-commit listener above. */
    private void detectForNewDataset(Long projectId, Long datasetId) {
        try {
            datasetRepository.findById(datasetId)
                    .filter(dataset -> dataset.getProject().getId().equals(projectId))
                    .ifPresent(dataset -> scanProject(dataset.getProject(), dataset));
        } catch (Exception e) {
            log.error("Automatic relationship detection failed for dataset {}", datasetId, e);
        }
    }

    /** Full pairwise scan across every analysed dataset of the project. */

    private ScanResponse scanProject(Project project, Dataset targetOnly) {
        List<Dataset> datasets = datasetRepository.findByProjectId(project.getId());
        Map<Long, Dataset> byId = new LinkedHashMap<>();
        Map<Long, AnalysisResult> analyses = new HashMap<>();
        for (Dataset dataset : datasets) {
            byId.put(dataset.getId(), dataset);
            analysisResultRepository.findByDatasetId(dataset.getId())
                    .ifPresent(result -> analyses.put(dataset.getId(), result));
        }
        List<DatasetRelationship> existing =
                relationshipRepository.findByProjectIdOrderByCreatedAtDesc(project.getId());

        int scannedPairs = 0;
        int created = 0;
        Map<String, Set<String>> valueCache = new HashMap<>();
        for (int i = 0; i < datasets.size(); i++) {
            for (int j = i + 1; j < datasets.size(); j++) {
                Dataset first = datasets.get(i);
                Dataset second = datasets.get(j);
                if (targetOnly != null
                        && !first.getId().equals(targetOnly.getId())
                        && !second.getId().equals(targetOnly.getId())) {
                    continue;
                }
                AnalysisResult firstAnalysis = analyses.get(first.getId());
                AnalysisResult secondAnalysis = analyses.get(second.getId());
                if (firstAnalysis == null || secondAnalysis == null) {
                    continue; // both sides need completed analyses
                }
                if (sameLineage(byId, first, second)) {
                    continue; // original vs its cleaned versions: not relationships
                }
                scannedPairs++;
                created += detectBetween(first, second, firstAnalysis, secondAnalysis,
                        existing, valueCache);
                created += detectSiblingBetween(first, second,
                        firstAnalysis, secondAnalysis, existing);
            }
        }
        if (created > 0) {
            projectContextCache.invalidate(project.getId());
        }
        return new ScanResponse(scannedPairs, created);
    }

    private int detectBetween(
            Dataset first, Dataset second,
            AnalysisResult firstAnalysis, AnalysisResult secondAnalysis,
            List<DatasetRelationship> existing,
            Map<String, Set<String>> valueCache) {
        Map<String, AnalyticsColumnStats> columnsFirst =
                parseColumns(firstAnalysis.getColumnStatsJson());
        Map<String, AnalyticsColumnStats> columnsSecond =
                parseColumns(secondAnalysis.getColumnStatsJson());
        log.debug("Detecting between {} ({}) and {} ({}): common candidates...",
                first.getName(), columnsFirst.keySet(), second.getName(), columnsSecond.keySet());

        int createdCount = 0;
        for (Map.Entry<String, AnalyticsColumnStats> entry : columnsFirst.entrySet()) {
            String nameKey = entry.getKey();
            AnalyticsColumnStats statsFirst = entry.getValue();
            AnalyticsColumnStats statsSecond = columnsSecond.get(nameKey);
            if (statsSecond == null) {
                continue; // v1: case-insensitive exact name matches only
            }
            boolean candidate = "IDENTIFIER".equals(statsFirst.semanticRole())
                    || "IDENTIFIER".equals(statsSecond.semanticRole());
            if (!candidate) {
                continue;
            }
            log.debug("Candidate column '{}' between {} and {}", nameKey,
                    first.getName(), second.getName());

            Set<String> valuesFirst = valuesOf(first, statsFirst.name(), valueCache);
            Set<String> valuesSecond = valuesOf(second, statsSecond.name(), valueCache);
            if (valuesFirst.isEmpty() || valuesSecond.isEmpty()) {
                continue;
            }
            double matchFirstInSecond = containment(valuesFirst, valuesSecond);
            double matchSecondInFirst = containment(valuesSecond, valuesFirst);
            log.debug("Column '{}': {}->{} = {}%, {}->{} = {}%",
                    nameKey, first.getName(), second.getName(), matchFirstInSecond,
                    second.getName(), first.getName(), matchSecondInFirst);

            // Orient child->parent using PK-ness: a parent key column holds
            // unique values (uniqueCount ~= rowCount), an FK column repeats.
            // When both sides are equally (non-)unique the containment share
            // decides, and if that is tied too the orientation is genuinely
            // ambiguous and this simply picks a stable primary framing.
            double pkFirst = uniquenessRatio(first, statsFirst);
            double pkSecond = uniquenessRatio(second, statsSecond);
            Dataset child;
            Dataset parent;
            AnalyticsColumnStats childStats;
            AnalyticsColumnStats parentStats;
            double matchPercentage;
            if (pkFirst > pkSecond + UNIQUENESS_EPSILON) {
                parent = first;
                child = second;
                parentStats = statsFirst;
                childStats = statsSecond;
            } else if (pkSecond > pkFirst + UNIQUENESS_EPSILON) {
                parent = second;
                child = first;
                parentStats = statsSecond;
                childStats = statsFirst;
            } else {
                child = first;
                parent = second;
                childStats = statsFirst;
                parentStats = statsSecond;
            }
            matchPercentage = containment(
                    valuesOf(child, childStats.name(), valueCache),
                    valuesOf(parent, parentStats.name(), valueCache));
            log.debug("Column '{}': {}->{} = {}%, {}->{} = {}%, uniqueness {}/{}",
                    nameKey, first.getName(), second.getName(), matchFirstInSecond,
                    second.getName(), first.getName(), matchSecondInFirst, pkFirst, pkSecond);

            if (matchPercentage < MIN_MATCH_PERCENTAGE) {
                continue; // coincidental name match, not a real link
            }

            if (existsAnyStatus(existing, first.getId(), second.getId(),
                    statsFirst.name(), statsSecond.name())) {
                continue; // already suggested/confirmed/manual/rejected before
            }

            DatasetRelationship relationship = DatasetRelationship.builder()
                    .project(child.getProject())
                    .datasetA(child)
                    .datasetB(parent)
                    .sharedColumnA(childStats.name())
                    .sharedColumnB(parentStats.name())
                    .matchPercentage(Math.round(matchPercentage * 10.0) / 10.0)
                    .status(RelationshipStatus.SUGGESTED)
                    .build();
            relationship = relationshipRepository.save(relationship);
            existing.add(relationship);
            createdCount++;
        }
        return createdCount;
    }

    /**
     * Sibling detection: two analysed datasets whose column names overlap by
     * at least {@link #SIBLING_MIN_OVERLAP_PERCENT} are flagged as schema
     * twins. Skipped entirely for pairs that already have a FOREIGN_KEY
     * relationship (any status) — a lookup table is not a sibling of its fact
     * table — or that were already flagged as siblings.
     */
    private int detectSiblingBetween(
            Dataset first, Dataset second,
            AnalysisResult firstAnalysis, AnalysisResult secondAnalysis,
            List<DatasetRelationship> existing) {
        if (existsForeignKeyBetween(existing, first.getId(), second.getId())
                || existsSiblingBetween(existing, first.getId(), second.getId())) {
            return 0;
        }
        Map<String, AnalyticsColumnStats> columnsFirst =
                parseColumns(firstAnalysis.getColumnStatsJson());
        Map<String, AnalyticsColumnStats> columnsSecond =
                parseColumns(secondAnalysis.getColumnStatsJson());
        if (columnsFirst.isEmpty() || columnsSecond.isEmpty()) {
            return 0;
        }

        int sharedCount = 0;
        String firstSharedName = null;
        String secondSharedName = null;
        for (Map.Entry<String, AnalyticsColumnStats> entry : columnsFirst.entrySet()) {
            AnalyticsColumnStats statsSecond = columnsSecond.get(entry.getKey());
            if (statsSecond == null) {
                continue; // case-insensitive exact name matches only
            }
            sharedCount++;
            if (firstSharedName == null) {
                firstSharedName = entry.getValue().name();
                secondSharedName = statsSecond.name();
            }
        }

        int maxColumns = Math.max(columnsFirst.size(), columnsSecond.size());
        double overlapPercent = 100.0 * sharedCount / maxColumns;
        if (overlapPercent < SIBLING_MIN_OVERLAP_PERCENT) {
            return 0;
        }

        DatasetRelationship relationship = DatasetRelationship.builder()
                .project(first.getProject())
                .datasetA(first)
                .datasetB(second)
                .sharedColumnA(firstSharedName)
                .sharedColumnB(secondSharedName)
                .matchPercentage(Math.round(overlapPercent * 10.0) / 10.0)
                .status(RelationshipStatus.SUGGESTED)
                .relationshipType(DatasetRelationship.RelationshipType.SIBLING)
                .build();
        relationship = relationshipRepository.save(relationship);
        existing.add(relationship);
        log.debug("Sibling pair detected: {} <-> {} ({}/{} columns, {}%)",
                first.getName(), second.getName(), sharedCount, maxColumns,
                Math.round(overlapPercent * 10.0) / 10.0);
        return 1;
    }

    /** Any FOREIGN_KEY relationship (any status/column) between the pair. */
    private boolean existsForeignKeyBetween(
            List<DatasetRelationship> existing, long dsA, long dsB) {
        long loDs = Math.min(dsA, dsB);
        long hiDs = Math.max(dsA, dsB);
        for (DatasetRelationship relationship : existing) {
            long rLo = Math.min(relationship.getDatasetA().getId(),
                    relationship.getDatasetB().getId());
            long rHi = Math.max(relationship.getDatasetA().getId(),
                    relationship.getDatasetB().getId());
            if (rLo == loDs && rHi == hiDs
                    && relationship.getRelationshipType()
                            == DatasetRelationship.RelationshipType.FOREIGN_KEY) {
                return true;
            }
        }
        return false;
    }

    /** Any SIBLING relationship (any status) between the pair. */
    private boolean existsSiblingBetween(
            List<DatasetRelationship> existing, long dsA, long dsB) {
        long loDs = Math.min(dsA, dsB);
        long hiDs = Math.max(dsA, dsB);
        for (DatasetRelationship relationship : existing) {
            long rLo = Math.min(relationship.getDatasetA().getId(),
                    relationship.getDatasetB().getId());
            long rHi = Math.max(relationship.getDatasetA().getId(),
                    relationship.getDatasetB().getId());
            if (rLo == loDs && rHi == hiDs
                    && relationship.getRelationshipType()
                            == DatasetRelationship.RelationshipType.SIBLING) {
                return true;
            }
        }
        return false;
    }

    /**
     * Direction-insensitive duplicate check keyed on the unordered dataset id
     * pair plus unordered case-insensitive column-name pair. REJECTED entries
     * match here too, which is what keeps rejected pairs from resurfacing.
     */
    private boolean existsAnyStatus(List<DatasetRelationship> existing,
                                    long dsA, long dsB, String colA, String colB) {
        long loDs = Math.min(dsA, dsB);
        long hiDs = Math.max(dsA, dsB);
        String loCol = colA.toLowerCase(Locale.ROOT).compareTo(colB.toLowerCase(Locale.ROOT)) <= 0
                ? colA.toLowerCase(Locale.ROOT) : colB.toLowerCase(Locale.ROOT);
        String hiCol = colA.toLowerCase(Locale.ROOT).compareTo(colB.toLowerCase(Locale.ROOT)) <= 0
                ? colB.toLowerCase(Locale.ROOT) : colA.toLowerCase(Locale.ROOT);
        for (DatasetRelationship relationship : existing) {
            long rLo = Math.min(relationship.getDatasetA().getId(), relationship.getDatasetB().getId());
            long rHi = Math.max(relationship.getDatasetA().getId(), relationship.getDatasetB().getId());
            if (rLo != loDs || rHi != hiDs) {
                continue;
            }
            String rColA = relationship.getSharedColumnA().toLowerCase(Locale.ROOT);
            String rColB = relationship.getSharedColumnB().toLowerCase(Locale.ROOT);
            String rLoCol = rColA.compareTo(rColB) <= 0 ? rColA : rColB;
            String rHiCol = rColA.compareTo(rColB) <= 0 ? rColB : rColA;
            if (rLoCol.equals(loCol) && rHiCol.equals(hiCol)) {
                return true;
            }
        }
        return false;
    }

    /** True when one dataset is an ancestor of the other via sourceDatasetId. */
    private boolean sameLineage(Map<Long, Dataset> byId, Dataset a, Dataset b) {
        return ancestorIds(byId, a.getId()).contains(b.getId())
                || ancestorIds(byId, b.getId()).contains(a.getId());
    }

    private Set<Long> ancestorIds(Map<Long, Dataset> byId, Long startId) {
        Set<Long> ancestors = new HashSet<>();
        Long cursor = byId.containsKey(startId) ? byId.get(startId).getSourceDatasetId() : null;
        while (cursor != null && !ancestors.contains(cursor)) {
            ancestors.add(cursor);
            Dataset parent = byId.get(cursor);
            cursor = parent == null ? null : parent.getSourceDatasetId();
        }
        return ancestors;
    }

    /** Distinct non-blank values of a column, memoised per dataset+column. */
    private Set<String> valuesOf(Dataset dataset, String column, Map<String, Set<String>> cache) {
        String key = dataset.getId() + "|" + column.toLowerCase(Locale.ROOT);
        Set<String> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Set<String> values = new HashSet<>();
        try (CSVParser parser = CsvSupport.open(
                fileStorageService.resolveExisting(dataset.getStoredFilePath()))) {
            Integer index = parser.getHeaderMap() == null ? null
                    : parser.getHeaderMap().get(column);
            if (index != null) {
                for (CSVRecord record : parser) {
                    if (record.size() <= index) {
                        continue;
                    }
                    String raw = record.get(index);
                    if (raw != null && !raw.isBlank()) {
                        values.add(raw.trim());
                        if (values.size() >= MAX_VALUES_PER_COLUMN) {
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not read column '{}' of dataset {} for matching", column, dataset.getId(), e);
        }
        cache.put(key, values);
        return values;
    }

    /**
     * Share of rows holding a distinct value; ~1.0 marks a fully unique key
     * column (the PK side), lower values a repeating FK-style column.
     */
    private static double uniquenessRatio(Dataset dataset, AnalyticsColumnStats stats) {
        int rows = dataset.getRowCount() == null ? 0 : dataset.getRowCount();
        if (rows <= 0) {
            return 0.0;
        }
        return stats.uniqueCount() / (double) rows;
    }

    /** Share of childValues present in parentValues, in percent. */
    private static double containment(Set<String> childValues, Set<String> parentValues) {
        int found = 0;
        for (String value : childValues) {
            if (parentValues.contains(value)) {
                found++;
            }
        }
        return 100.0 * found / childValues.size();
    }

    private Dataset findProjectDataset(Long projectId, Long datasetId, String field) {
        Dataset dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new InvalidRelationshipException(
                        field + " refers to dataset " + datasetId + " which does not exist."));
        if (!dataset.getProject().getId().equals(projectId)) {
            throw new InvalidRelationshipException(
                    field + " refers to dataset " + datasetId + " which is not part of this project.");
        }
        return dataset;
    }

    private RelationshipResponse toResponse(DatasetRelationship relationship) {
        return new RelationshipResponse(
                relationship.getId(),
                relationship.getProject().getId(),
                relationship.getDatasetA().getId(),
                relationship.getDatasetA().getName(),
                relationship.getDatasetB().getId(),
                relationship.getDatasetB().getName(),
                relationship.getSharedColumnA(),
                relationship.getSharedColumnB(),
                relationship.getMatchPercentage(),
                relationship.getStatus(),
                relationship.getRelationshipType(),
                relationship.getCreatedAt(),
                relationship.getUpdatedAt());
    }

    /** Column name (lowercased) to stats, from a stored columnStatsJson array. */
    private Map<String, AnalyticsColumnStats> parseColumns(String columnStatsJson) {
        Map<String, AnalyticsColumnStats> out = new HashMap<>();
        if (columnStatsJson == null || columnStatsJson.isBlank()) {
            return out;
        }
        try {
            AnalyticsColumnStats[] columns =
                    objectMapper.readValue(columnStatsJson, AnalyticsColumnStats[].class);
            for (AnalyticsColumnStats column : columns) {
                if (column.name() != null) {
                    out.put(column.name().toLowerCase(Locale.ROOT), column);
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse stored column stats JSON", e);
        }
        return out;
    }
}
