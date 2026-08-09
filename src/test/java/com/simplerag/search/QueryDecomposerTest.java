package com.simplerag.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryDecomposerTest {
    private final QueryDecomposer decomposer = new QueryDecomposer();

    @Test
    void singleFacetQuestionIsReturnedVerbatim() {
        // The guarantee the whole design rests on: one-facet questions retrieve exactly as before.
        assertEquals(List.of("如何连接数据库？"), decomposer.decompose("如何连接数据库？"));
        assertEquals(List.of("接口超时重试相关代码在哪"), decomposer.decompose("接口超时重试相关代码在哪"));
        assertEquals(List.of("where is the retry implementation"),
                decomposer.decompose("where is the retry implementation"));
    }

    @Test
    void splitsOnMultipleQuestionMarks() {
        List<String> facets = decomposer.decompose("什么是索引？它怎么创建？");

        assertEquals(List.of("什么是索引", "它怎么创建"), facets);
    }

    @Test
    void splitsOnChineseConjunction() {
        List<String> facets = decomposer.decompose("配置文件在哪里，以及默认值是多少");

        assertEquals(List.of("配置文件在哪里", "默认值是多少"), facets);
    }

    @Test
    void splitsOnEnglishConjunction() {
        List<String> facets = decomposer.decompose(
                "where is the retry policy defined as well as which key sets the timeout");

        assertEquals(2, facets.size());
        assertTrue(facets.get(0).contains("retry policy"));
        assertTrue(facets.get(1).contains("timeout"));
    }

    @Test
    void doesNotSplitOnACommaJoiningOneSubject() {
        // "重试、超时和降级的配置" is one subject enumerated, not three questions.
        assertEquals(List.of("重试、超时和降级的配置"), decomposer.decompose("重试、超时和降级的配置"));
    }

    @Test
    void fallsBackToTheOriginalWhenEveryFragmentIsTooShort() {
        assertEquals(List.of("A？B？C"), decomposer.decompose("A？B？C"));
    }

    @Test
    void adverbialConjunctionDoesNotProduceASpuriousSplit() {
        // "同时" here modifies one ask; the leading fragment is empty so only one facet survives.
        assertEquals(List.of("同时支持中文和英文检索吗"), decomposer.decompose("同时支持中文和英文检索吗"));
    }

    @Test
    void capsAtThreeSubQueries() {
        List<String> facets = decomposer.decompose("A是什么？C怎么用？D为什么？E的作用？F在哪里？");

        assertEquals(QueryDecomposer.MAX_SUB_QUERIES, facets.size());
    }

    @Test
    void dropsDuplicateFacetsThatDifferOnlyInWidthOrCase() {
        List<String> facets = decomposer.decompose("JDBC connection pool？ｊｄｂｃ connection pool？超时怎么配");

        assertEquals(2, facets.size());
        assertTrue(facets.get(1).contains("超时"));
    }

    @Test
    void neverReturnsAnEmptyList() {
        assertEquals(1, decomposer.decompose("").size());
        assertEquals(1, decomposer.decompose(null).size());
        assertEquals(1, decomposer.decompose("   ").size());
        assertFalse(decomposer.decompose("？？？").isEmpty());
    }
}
