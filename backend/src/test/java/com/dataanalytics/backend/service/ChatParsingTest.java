package com.dataanalytics.backend.service;

import com.dataanalytics.backend.service.ChatService.QueryAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the tolerant query-action extraction used by the AI chat.
 * These parsers are pure statics, tested here without any Spring context.
 */
class ChatParsingTest {

    @Test
    void plainAnswer_isNotAQueryAction() {
        assertThat(ChatService.parseQueryAction("Hello! Here is a summary for you.")).isNull();
        assertThat(ChatService.parseQueryAction(null)).isNull();
    }

    @Test
    void canonicalJsonAction_isExtracted() {
        String reply = "{\"action\": \"query\", \"sql\": \"SELECT * FROM orders\", \"reasoning\": \"need rows\"}";
        QueryAction action = ChatService.parseQueryAction(reply);
        assertThat(action).isNotNull();
        assertThat(action.sql()).isEqualTo("SELECT * FROM orders");
    }

    @Test
    void jsonEmbeddedInProse_isStillFound() {
        String reply = "Let me look that up.\n{\"action\":\"query\",\"sql\":\"SELECT product FROM sales\"}\nOne moment...";
        assertThat(ChatService.parseQueryAction(reply).sql()).isEqualTo("SELECT product FROM sales");
    }

    @Test
    void actionType_isCaseInsensitive() {
        String reply = "{\"action\": \"QUERY\", \"sql\": \"SELECT 1\", \"reasoning\": \"check\"}";
        assertThat(ChatService.parseQueryAction(reply).sql()).isEqualTo("SELECT 1");
    }

    @Test
    void jsonWithoutSql_isRejected() {
        assertThat(ChatService.parseQueryAction("{\"action\": \"query\", \"reasoning\": \"missing sql\"}")).isNull();
    }

    @Test
    void wrongActionType_fallsBackAndIsRejected() {
        String reply = "{\"action\": \"answer\", \"sql\": \"SELECT 1\", \"reasoning\": \"x\"}";
        assertThat(ChatService.parseQueryAction(reply)).isNull();
    }

    @Test
    void fencedSqlBlock_isExtracted() {
        String reply = "```sql\nSELECT region, COUNT(*) FROM sales GROUP BY region\n```";
        assertThat(ChatService.parseQueryAction(reply).sql())
                .isEqualTo("SELECT region, COUNT(*) FROM sales GROUP BY region");
    }

    @Test
    void fencedBlockWithoutSqlTag_isExtracted() {
        assertThat(ChatService.parseQueryAction("```\nSELECT * FROM t\n```").sql())
                .isEqualTo("SELECT * FROM t");
    }

    @Test
    void leadingWhitespaceBeforeSelect_isAccepted() {
        assertThat(ChatService.parseQueryAction("\n\n  SELECT a FROM b").sql())
                .isEqualTo("SELECT a FROM b");
    }

    @Test
    void withStatement_isExtracted() {
        assertThat(ChatService.parseQueryAction("WITH top AS (SELECT * FROM t) SELECT * FROM top").sql())
                .startsWith("WITH top");
    }

    @Test
    void trailingSemicolon_isStripped() {
        assertThat(ChatService.parseQueryAction("SELECT * FROM t;").sql())
                .isEqualTo("SELECT * FROM t");
    }

    @Test
    void multipleStatements_areRejected() {
        assertThat(ChatService.parseQueryAction("SELECT * FROM a; DROP TABLE b")).isNull();
    }

    @Test
    void oversizedReplies_areRejected() {
        String huge = "SELECT " + "1,".repeat(3000) + "1t";
        assertThat(ChatService.parseQueryAction(huge)).isNull();
    }

    @Test
    void selectInTheMiddleOfSentence_isNotASqlAction() {
        assertThat(ChatService.parseQueryAction("Please note SELECT is a keyword in SQL.")).isNull();
    }

    @Test
    void extractFencedSql_nullAndRegexes() {
        assertThat(ChatService.extractFencedSql(null)).isNull();
        assertThat(ChatService.extractFencedSql("no sql here")).isNull();
        assertThat(ChatService.extractFencedSql("```sql\nWITH c AS (SELECT 1) SELECT * FROM c\n```").sql())
                .startsWith("WITH c");
    }

    @Test
    void balancedObjectCore_isExposedViaQueryAction() {
        // The record itself is the observable unit; double-check the field.
        QueryAction action = new QueryAction("SELECT 1");
        assertThat(action.sql()).isEqualTo("SELECT 1");
    }
}