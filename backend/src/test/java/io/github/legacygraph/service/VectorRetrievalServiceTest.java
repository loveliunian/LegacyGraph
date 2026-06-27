package io.github.legacygraph.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
class VectorRetrievalServiceTest {

    @Test
    void testConstructor_CreatesInstance() {
        VectorRetrievalService service = new VectorRetrievalService();
        assertNotNull(service);
        assertDoesNotThrow(() -> {});
    }
}
