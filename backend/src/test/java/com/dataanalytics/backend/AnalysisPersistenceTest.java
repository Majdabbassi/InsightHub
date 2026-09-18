package com.dataanalytics.backend;

import com.dataanalytics.backend.model.AnalysisResult;
import com.dataanalytics.backend.model.Dataset;
import com.dataanalytics.backend.model.Project;
import com.dataanalytics.backend.model.Role;
import com.dataanalytics.backend.model.User;
import com.dataanalytics.backend.repository.AnalysisResultRepository;
import com.dataanalytics.backend.repository.DatasetRepository;
import com.dataanalytics.backend.repository.ProjectRepository;
import com.dataanalytics.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnalysisPersistenceTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private AnalysisResultRepository analysisResultRepository;

    private Dataset dataset;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .email("owner@test.com")
                .password("encoded")
                .fullName("Test Owner")
                .role(Role.USER)
                .build());

        Project project = projectRepository.save(Project.builder()
                .name("Test Project")
                .owner(user)
                .build());

        dataset = datasetRepository.save(Dataset.builder()
                .name("sales.csv")
                .originalFilename("sales.csv")
                .storedFilePath("/uploads/test/sales.csv")
                .fileSizeBytes(1024L)
                .project(project)
                .build());
    }

    @Test
    void persistAndRetrieve_analysisResult() {
        AnalysisResult saved = analysisResultRepository.save(
                AnalysisResult.builder()
                        .dataset(dataset)
                        .rowCount(100)
                        .columnCount(5)
                        .duplicateRowCount(3)
                        .totalMissingValues(12L)
                        .columnStatsJson("[{\"name\":\"revenue\"}]")
                        .correlationsJson("[]")
                        .dataQualityJson("{\"overallScore\":87}")
                        .build());

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAnalyzedAt()).isNotNull();

        AnalysisResult retrieved = analysisResultRepository.findByDatasetId(dataset.getId()).orElseThrow();
        assertThat(retrieved.getRowCount()).isEqualTo(100);
        assertThat(retrieved.getColumnCount()).isEqualTo(5);
        assertThat(retrieved.getColumnStatsJson()).isEqualTo("[{\"name\":\"revenue\"}]");
        assertThat(retrieved.getDataQualityJson()).isEqualTo("{\"overallScore\":87}");
    }

    @Test
    void analysisResult_isUniquePerDataset() {
        analysisResultRepository.save(AnalysisResult.builder()
                .dataset(dataset)
                .rowCount(50)
                .columnCount(3)
                .duplicateRowCount(0)
                .totalMissingValues(0L)
                .columnStatsJson("[]")
                .build());

        analysisResultRepository.flush();
        assertThat(analysisResultRepository.findByDatasetId(dataset.getId()))
                .isPresent()
                .get()
                .extracting(AnalysisResult::getRowCount)
                .isEqualTo(50);

        // The one-to-one mapping is enforced by the unique constraint on
        // dataset_id: a second result for the same dataset must be rejected.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            analysisResultRepository.saveAndFlush(AnalysisResult.builder()
                    .dataset(dataset)
                    .rowCount(200)
                    .columnCount(3)
                    .duplicateRowCount(1)
                    .totalMissingValues(5L)
                    .columnStatsJson("[{\"name\":\"qty\"}]")
                    .build());
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
