package io.github.legacygraph.qa.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * QA 评测用例
 */
@Data
public class QaTestCase {
    private String id;
    private String category;
    private String question;
    
    @JsonProperty("expected_keywords")
    private List<String> expectedKeywords;
    
    @JsonProperty("expected_evidence_types")
    private List<String> expectedEvidenceTypes;
    
    private String difficulty;
    private Map<String, String> context;
}
