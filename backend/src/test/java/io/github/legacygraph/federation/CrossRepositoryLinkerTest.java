package io.github.legacygraph.federation;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.federation.CrossRepositoryLinker.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossRepositoryLinkerTest {

    private CrossRepositoryLinker linker;

    @BeforeEach
    void setUp() {
        linker = new CrossRepositoryLinker();
    }

    @Test
    void sharedTableWriterAffectsReaderInAnotherProject() {
        List<CrossRepositoryLinkCandidate> links = linker.linkSharedTableAccess(List.of(
            new TableAccess("order-service", "method:createOrder", "orders", "WRITE"),
            new TableAccess("settlement-service", "method:settleOrder", "orders", "READ")
        ));
        
        assertThat(links).hasSize(1);
        assertThat(links.get(0).edgeType()).isEqualTo(EdgeType.AFFECTS);
        assertThat(links.get(0).fromProjectId()).isEqualTo("order-service");
        assertThat(links.get(0).toProjectId()).isEqualTo("settlement-service");
        assertThat(links.get(0).confidence()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void sharedTableSameProjectNoLink() {
        List<CrossRepositoryLinkCandidate> links = linker.linkSharedTableAccess(List.of(
            new TableAccess("order-service", "method:createOrder", "orders", "WRITE"),
            new TableAccess("order-service", "method:getOrder", "orders", "READ")
        ));
        
        assertThat(links).isEmpty();
    }

    @Test
    void multipleWritersAndReaders() {
        List<CrossRepositoryLinkCandidate> links = linker.linkSharedTableAccess(List.of(
            new TableAccess("project-a", "method:write1", "shared_table", "WRITE"),
            new TableAccess("project-a", "method:write2", "shared_table", "WRITE"),
            new TableAccess("project-b", "method:read1", "shared_table", "READ"),
            new TableAccess("project-c", "method:read2", "shared_table", "READ")
        ));
        
        // 2 writers × 2 readers in different projects = 4 links
        assertThat(links).hasSize(4);
        assertThat(links).allMatch(l -> l.edgeType() == EdgeType.AFFECTS);
    }

    @Test
    void externalApiProviderToCaller() {
        List<CrossRepositoryLinkCandidate> links = linker.linkExternalApiCalls(List.of(
            new ExternalApiCall("user-service", "controller:UserController", "/api/users", "GET", true),
            new ExternalApiCall("order-service", "client:UserClient", "/api/users", "GET", false)
        ));
        
        assertThat(links).hasSize(1);
        assertThat(links.get(0).edgeType()).isEqualTo(EdgeType.CALLS_EXTERNAL);
        assertThat(links.get(0).fromProjectId()).isEqualTo("user-service");
        assertThat(links.get(0).toProjectId()).isEqualTo("order-service");
        assertThat(links.get(0).confidence()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void externalApiSameProjectNoLink() {
        List<CrossRepositoryLinkCandidate> links = linker.linkExternalApiCalls(List.of(
            new ExternalApiCall("user-service", "controller:UserController", "/api/users", "GET", true),
            new ExternalApiCall("user-service", "internal:UserInternal", "/api/users", "GET", false)
        ));
        
        assertThat(links).isEmpty();
    }

    @Test
    void topicProducerTriggersConsumer() {
        List<CrossRepositoryLinkCandidate> links = linker.linkTopicMessages(List.of(
            new TopicAccess("order-service", "method:createOrder", "order-events", "PRODUCE"),
            new TopicAccess("inventory-service", "method:updateStock", "order-events", "CONSUME")
        ));
        
        assertThat(links).hasSize(1);
        assertThat(links.get(0).edgeType()).isEqualTo(EdgeType.TRIGGERS);
        assertThat(links.get(0).fromProjectId()).isEqualTo("order-service");
        assertThat(links.get(0).toProjectId()).isEqualTo("inventory-service");
        assertThat(links.get(0).confidence()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void topicSameProjectNoLink() {
        List<CrossRepositoryLinkCandidate> links = linker.linkTopicMessages(List.of(
            new TopicAccess("order-service", "method:createOrder", "order-events", "PRODUCE"),
            new TopicAccess("order-service", "method:logOrder", "order-events", "CONSUME")
        ));
        
        assertThat(links).isEmpty();
    }

    @Test
    void linkAllCombinesAllTypes() {
        List<TableAccess> tables = List.of(
            new TableAccess("p1", "n1", "t1", "WRITE"),
            new TableAccess("p2", "n2", "t1", "READ")
        );
        
        List<ExternalApiCall> apis = List.of(
            new ExternalApiCall("p3", "n3", "/api", "GET", true),
            new ExternalApiCall("p4", "n4", "/api", "GET", false)
        );
        
        List<TopicAccess> topics = List.of(
            new TopicAccess("p5", "n5", "topic1", "PRODUCE"),
            new TopicAccess("p6", "n6", "topic1", "CONSUME")
        );
        
        List<CrossRepositoryLinkCandidate> all = linker.linkAll(tables, apis, topics);
        
        assertThat(all).hasSize(3);
        assertThat(all).anyMatch(l -> l.edgeType() == EdgeType.AFFECTS);
        assertThat(all).anyMatch(l -> l.edgeType() == EdgeType.CALLS_EXTERNAL);
        assertThat(all).anyMatch(l -> l.edgeType() == EdgeType.TRIGGERS);
    }

    @Test
    void candidateKeyIsUnique() {
        CrossRepositoryLinkCandidate c1 = new CrossRepositoryLinkCandidate(
            "p1", "n1", "p2", "n2", EdgeType.AFFECTS, 0.9, "reason1", Set.of("e1")
        );
        CrossRepositoryLinkCandidate c2 = new CrossRepositoryLinkCandidate(
            "p1", "n1", "p2", "n2", EdgeType.AFFECTS, 0.8, "reason2", Set.of("e2")
        );
        
        assertThat(c1.toCandidateKey()).isEqualTo(c2.toCandidateKey());
    }

    @Test
    void candidateWithoutEvidenceThrows() {
        assertThatThrownBy(() -> new CrossRepositoryLinkCandidate(
            "p1", "n1", "p2", "n2", EdgeType.AFFECTS, 0.9, "reason", Set.of()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("必须提供证据");
    }

    @Test
    void federatedGraphScopeValidation() {
        assertThatThrownBy(() -> new FederatedGraphScope("", "sys1", "p1", "repo1", "main"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId");
        
        assertThatThrownBy(() -> new FederatedGraphScope("t1", "", "p1", "repo1", "main"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("systemId");
    }

    @Test
    void federatedGraphScopeKeyFormat() {
        FederatedGraphScope scope = new FederatedGraphScope("tenant1", "system1", "project1", "https://github.com/repo", "main");
        assertThat(scope.toScopeKey()).isEqualTo("tenant1:system1:project1:https://github.com/repo:main");
    }
}
