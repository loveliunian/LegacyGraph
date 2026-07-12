package io.github.legacygraph.builder.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldSimilarityCalculatorTest {

    @Test
    void testPartialMatch() {
        // {id, name} vs {id, name, age} → Jaccard 2/3 ≈ 0.6666，LCS 2/3 ≈ 0.6666
        String[] a = {"id", "name"};
        String[] b = {"id", "name", "age"};
        double sim = FieldSimilarityCalculator.similarity(a, b);
        assertEquals(0.6666, sim, 0.02);
    }

    @Test
    void testEmptyArray() {
        assertEquals(0.0, FieldSimilarityCalculator.similarity(new String[0], new String[]{"id"}));
        assertEquals(0.0, FieldSimilarityCalculator.similarity(new String[]{"id"}, new String[0]));
        assertEquals(0.0, FieldSimilarityCalculator.similarity(new String[0], new String[0]));
    }

    @Test
    void testNull() {
        assertEquals(0.0, FieldSimilarityCalculator.similarity(null, new String[]{"id"}));
        assertEquals(0.0, FieldSimilarityCalculator.similarity(new String[]{"id"}, null));
        assertEquals(0.0, FieldSimilarityCalculator.similarity(null, null));
    }

    @Test
    void testCompleteMatch() {
        String[] a = {"id", "name", "age"};
        String[] b = {"id", "name", "age"};
        assertEquals(1.0, FieldSimilarityCalculator.similarity(a, b), 0.001);
    }

    @Test
    void testCompleteMismatch() {
        String[] a = {"id", "name"};
        String[] b = {"foo", "bar"};
        assertEquals(0.0, FieldSimilarityCalculator.similarity(a, b), 0.001);
    }

    @Test
    void testCaseInsensitive() {
        String[] a = {"ID", "Name"};
        String[] b = {"id", "name"};
        assertEquals(1.0, FieldSimilarityCalculator.similarity(a, b), 0.001);
    }
}
