package com.dataanalytics.backend.service;

import com.dataanalytics.backend.dto.ChatDtos;
import com.dataanalytics.backend.dto.OllamaChatDtos.OllamaMessage;
import com.dataanalytics.backend.dto.QueryResultResponse;
import com.dataanalytics.backend.model.ChatConversation;
import com.dataanalytics.backend.model.ChatMessage;
import com.dataanalytics.backend.model.Dataset;
import com.dataanalytics.backend.model.Project;
import com.dataanalytics.backend.repository.ChatConversationRepository;
import com.dataanalytics.backend.repository.ChatMessageRepository;
import com.dataanalytics.backend.repository.DatasetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One conversation per project (v1). Each question is answered by Ollama with:
 * system prompt + freshly rendered project context + the last HISTORY_WINDOW
 * messages + the new question. Both the user message and the reply are only
 * persisted after a successful answer, so a failed call leaves no trace.
 *
 * Phase B: when the first reply is a {"action":"query",...} request instead
 * of a plain answer, the proposed SQL is validated and executed against the
 * referenced dataset files in the analytics service's sandboxed DuckDB, and
 * a second model call produces the final grounded answer. Exactly ONE query
 * round-trip is allowed per question; the executed SQL is stored on the
 * assistant message for transparency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    /** Bare identifiers after FROM/JOIN (comma lists included). */
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*)");
    /** CTE aliases defined by the query itself, optionally with columns. */
    private static final Pattern CTE_ALIAS = Pattern.compile(
            "(?i)\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\([^)]*\\))?\\s*AS\\s*\\(");

    /**
     * Single, thread-safe reader for the tolerant JSON extraction in
     * {@link #parseQueryAction}. The parser is a pure static helper (and is
     * unit-tested as such), so it does not warrant the Spring-configured
     * mapper; one shared instance avoids allocating a mapper per reply.
     */
    private static final ObjectMapper JSON_READER = new ObjectMapper();

    /**
     * Grounding rules for the assistant. The rendered project context is
     * appended to this exact text in the same system message.
     */
    private static final String SYSTEM_PROMPT = """
            You are a data analytics assistant embedded in a web application. \
            You help users understand their data projects. \
            Answer ONLY using the PROJECT CONTEXT provided below. \
            Do not invent facts, numbers, column names, dataset names, or relationships that are not present there. \
            If the answer is not contained in the context (for example, row-level details you were not given), \
            say clearly that you don't have that information and suggest what the user could check in the app instead. \
            Be concise and factual.

            If - and only if - answering correctly requires row-level detail that the context \
            does not contain, you may instead request ONE live read-only SQL query over the raw rows. \
            To do that, reply with ONLY this JSON object and nothing else: \
            {"action": "query", "sql": "SELECT product, SUM(quantity) AS total FROM some_table GROUP BY product ORDER BY total DESC LIMIT 1", "reasoning": "row-level totals are needed"}
            SQL rules:
            - Reference ONLY the table names listed in the "SQL query catalog" section of the project context.
            - Use ONLY column names exactly as they appear in that catalog, next to their table. \
            NEVER invent a column or borrow one from another table - if a column is not listed for a table, it does not exist.
            - The statement must start with SELECT or WITH and consist of exactly one statement.
            - Keep queries SIMPLE: a plain SELECT with JOIN and GROUP BY works best. \
            Avoid CTEs, window functions and exotic syntax unless truly necessary.
            - Base every query STRICTLY on the current question: never reuse filters, \
            ids or values that appeared in earlier conversation turns.
            - It must be strictly read-only: no INSERT, UPDATE, DELETE, DROP, ALTER, CREATE, TRUNCATE, ATTACH, COPY or PRAGMA.
            - Limit results to at most 50 rows.
            Never write SQL anywhere in a normal answer - SQL written outside the JSON action object \
            is NOT executed and will just be shown to the user as dead text. \
            When the user asks you to run/query/look up something, they want EXECUTED results, so use the JSON object. \
            If a query is not needed, not allowed, or impossible with the catalog tables, answer normally instead.""";
    // NOTE: the JSON example above intentionally sits on one line so the
    // model copies its exact shape; do not reflow it.

    /** User phrasings that signal an explicit wish for live SQL execution. */
    private static final Pattern QUERY_INTENT = Pattern.compile(
            "(?i)\\b(sql|query|run|execute|look ?up|find out|show me|reveal)\\b");

    /** Corrective follow-up used once when the model ignored the contract. */
    private static final String NUDGE_MESSAGE = """
            Your reply did not follow the required format, so no query was run. \
            Respond now with ONLY the JSON object {"action": "query", "sql": "<one SELECT statement>", "reasoning": "<short reason>"} \
            and no other text before or after it. Use only tables from the SQL query catalog.""";

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ProjectContextCache contextCache;
    private final ProjectContextBuilder contextBuilder;
    private final OllamaClient ollamaClient;
    private final DatasetRepository datasetRepository;
    private final FileStorageService fileStorageService;
    private final AnalyticsClient analyticsClient;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<ChatDtos.ChatMessageResponse> history(Project project) {
        return conversationRepository.findByProjectId(project.getId())
                .map(conversation -> messageRepository
                        .findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId()).stream()
                        .map(ChatService::toResponse)
                        .toList())
                .orElse(List.of());
    }

    @Transactional
    public void clearHistory(Project project) {
        conversationRepository.findByProjectId(project.getId())
                .ifPresent(conversation -> {
                    messageRepository.deleteByConversation(conversation);
                    conversationRepository.delete(conversation);
                });
    }

    @Transactional
    public ChatDtos.ChatMessageResponse ask(Project project, String userText) {
        ChatConversation conversation = getOrCreateConversation(project);

        List<ChatMessage> recent = new ArrayList<>(messageRepository
                .findTop6ByConversationIdOrderByCreatedAtDescIdAsc(conversation.getId()));
        Collections.reverse(recent);

        String context = contextCache.get(project.getId(),
                () -> contextBuilder.buildContext(project));

        List<OllamaMessage> messages = new ArrayList<>();
        messages.add(new OllamaMessage("system",
                SYSTEM_PROMPT + "\n\n=== PROJECT CONTEXT ===\n" + context));
        for (ChatMessage message : recent) {
            messages.add(toOllama(message));
        }
        messages.add(new OllamaMessage("user", userText));

        String firstReply = ollamaClient.chat(messages);

        String usedSql = null;
        QueryAction action = parseQueryAction(firstReply);
        if (action == null && QUERY_INTENT.matcher(userText).find()) {
            // The model ignored the JSON contract despite explicit query
            // intent - give it exactly ONE corrective chance.
            log.debug("Query intent detected but no action returned; nudging once");
            List<OllamaMessage> corrective = new ArrayList<>(messages);
            corrective.add(new OllamaMessage("assistant", firstReply));
            corrective.add(new OllamaMessage("user", NUDGE_MESSAGE));
            String retryReply = ollamaClient.chat(corrective);
            action = parseQueryAction(retryReply);
            if (action != null) {
                messages = corrective;
                firstReply = retryReply;
            } else {
                firstReply = retryReply;
            }
        }

        String reply = firstReply;
        if (action != null) {
            List<OllamaMessage> convo = new ArrayList<>(messages);
            convo.add(new OllamaMessage("assistant", firstReply));
            RoundTripResult roundTrip =
                    runQueryRoundTrip(project, convo, action, userText);
            reply = roundTrip.answer();
            usedSql = roundTrip.usedSql();
        }

        persist(conversation, ChatMessage.ChatRole.USER, userText);
        ChatMessage assistantMessage = persist(
                conversation, ChatMessage.ChatRole.ASSISTANT, reply, usedSql);
        log.debug("Chat answered for project {}: {} -> {} chars (query={})",
                project.getId(), userText.length(), reply.length(), usedSql != null);
        return toResponse(assistantMessage);
    }

    record RoundTripResult(String answer, String usedSql) {
    }

    /**
     * Executes the requested query and asks the model for the final answer.
     * The message list must already end with the assistant's actionable
     * reply. Engine-level SQL failures get ONE self-repair attempt (the
     * error is shown to the model, which proposes a corrected query);
     * unknown-table and transport failures are never repaired - they are
     * relayed honestly instead. The last executed SQL is returned for the
     * transparency disclosure.
     */
    private RoundTripResult runQueryRoundTrip(Project project, List<OllamaMessage> convo,
            QueryAction action, String originalQuestion) {
        List<OllamaMessage> followUp = new ArrayList<>(convo);
        QueryResultResponse result = executeFor(project, action.sql());
        String lastSql = action.sql();

        if (!result.success() && repairable(result)) {
            followUp.add(new OllamaMessage("user",
                    ("The query failed with this error: %s\n"
                            + "Reply with ONLY a corrected JSON object "
                            + "{\"action\": \"query\", \"sql\": \"...\", \"reasoning\": \"...\"} "
                            + "that fixes the problem. Use only columns and tables from "
                            + "the catalog, and prefer the SIMPLEST possible query: a plain "
                            + "SELECT with JOIN and GROUP BY.").formatted(result.error())));
            String fixedReply = ollamaClient.chat(followUp);
            QueryAction fixed = parseQueryAction(fixedReply);
            if (fixed != null) {
                followUp.add(new OllamaMessage("assistant", fixedReply));
                result = executeFor(project, fixed.sql());
                lastSql = fixed.sql();
            }
        }

        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            resultJson = "{\"success\": false}";
        }
        followUp.add(new OllamaMessage("user", """
                The SQL query you requested was executed. Result JSON: %s

                Now answer the user's CURRENT question ("%s") using these results. \
                Ignore any ids, filters or values from earlier turns unless this question \
                repeats them. Be concise and factual. If the query failed or returned \
                nothing useful, say exactly what went wrong - never invent data.""".formatted(
                resultJson, originalQuestion)));
        String second = ollamaClient.chat(followUp);
        // Never loop: if the second reply is another action request, treat it
        // as plain text and let the transparency of usedSql carry the rest.
        if (parseQueryAction(second) != null) {
            log.warn("Second reply was another query action; returning as text");
        }
        return new RoundTripResult(second, lastSql);
    }

    /** Only engine/schema errors can be fixed by proposing different SQL. */
    private static boolean repairable(QueryResultResponse result) {
        String error = result.error();
        return error != null && !error.contains("not part of this project")
                && !error.contains("could not be reached");
    }

    private QueryResultResponse executeFor(Project project, String sql) {
        ResolvedTables resolved = resolveTables(project, sql);
        if (!resolved.missing().isEmpty()) {
            return new QueryResultResponse(false, null, null, null,
                    "Query references table(s) that are not part of this project: "
                            + String.join(", ", resolved.missing())
                            + ". Allowed tables: "
                            + String.join(", ", resolved.available()) + ".");
        }
        try {
            return analyticsClient.executeQuery(sql, resolved.tables(), resolved.filenames());
        } catch (RuntimeException e) {
            log.warn("Query execution transport failed for project {}",
                    project.getId(), e);
            return new QueryResultResponse(false, null, null, null,
                    "The query service could not be reached.");
        }
    }

    record ResolvedTables(LinkedHashMap<String, Path> tables,
            Map<String, String> filenames,
            Set<String> missing,
            Set<String> available) {
    }

    /**
     * Maps the FROM/JOIN identifiers of the proposed SQL onto active datasets
     * of this project via their sanitized names. CTE aliases count as known.
     */
    private ResolvedTables resolveTables(Project project, String sql) {
        List<Dataset> active = datasetRepository.findByProjectId(project.getId());
        Set<Long> superseded = new HashSet<>();
        for (Dataset dataset : active) {
            if (dataset.getSourceDatasetId() != null) {
                superseded.add(dataset.getSourceDatasetId());
            }
        }
        List<Dataset> usable = active.stream()
                .filter(dataset -> !superseded.contains(dataset.getId()))
                .sorted(java.util.Comparator.comparing(Dataset::getId))
                .toList();

        // Shared naming source of truth: the same helper builds the SQL
        // catalog shown to the model, so advertised names always match the
        // names bound here.
        Map<Long, String> namesById = SqlTableNames.uniqueTableNames(usable);
        Map<String, Dataset> bySanitized = new LinkedHashMap<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Dataset dataset : usable) {
            String unique = namesById.get(dataset.getId());
            bySanitized.put(unique, dataset);
            mapping.put(unique, dataset.getOriginalFilename() == null
                    ? dataset.getName() : dataset.getOriginalFilename());
        }

        Matcher matcher = TABLE_REFERENCE.matcher(sql);
        Set<String> referenced = new LinkedHashSet<>();
        while (matcher.find()) {
            for (String name : matcher.group(1).split(",")) {
                referenced.add(name.trim().toLowerCase());
            }
        }
        Set<String> cteAliases = new HashSet<>();
        Matcher aliasMatcher = CTE_ALIAS.matcher(sql);
        while (aliasMatcher.find()) {
            cteAliases.add(aliasMatcher.group(1).toLowerCase());
        }
        referenced.removeAll(cteAliases);

        LinkedHashMap<String, Path> tables = new LinkedHashMap<>();
        Map<String, String> filenames = new LinkedHashMap<>();
        for (String name : referenced) {
            Dataset dataset = bySanitized.get(name);
            if (dataset == null) {
                continue;
            }
            tables.put(name, fileStorageService.resolveExisting(
                    dataset.getStoredFilePath()));
            filenames.put(name, mapping.get(name));
        }
        Set<String> missing = new LinkedHashSet<>(referenced);
        missing.removeAll(bySanitized.keySet());
        return new ResolvedTables(tables, filenames, missing, bySanitized.keySet());
    }

    record QueryAction(String sql) {
    }

    /**
     * Tolerant detection of a query-action reply: strips markdown fences,
     * extracts the first balanced JSON object, checks shape. Falls back to
     * accepting a bare fenced/leading SQL statement. Returns null for any
     * ordinary answer.
     */
    static QueryAction parseQueryAction(String reply) {
        if (reply == null) {
            return null;
        }
        if (reply.contains("{")) {
            String candidate = stripFences(reply).trim();
            int start = candidate.indexOf('{');
            String json = balancedObject(candidate, start);
            if (json != null) {
                try {
                    JsonNode node = JSON_READER.readTree(json);
                    JsonNode actionNode = node.get("action");
                    JsonNode sqlNode = node.get("sql");
                    if (actionNode != null && sqlNode != null
                            && "query".equalsIgnoreCase(actionNode.asText(""))
                            && !sqlNode.asText("").isBlank()) {
                        return new QueryAction(sqlNode.asText());
                    }
                } catch (Exception e) {
                    // fall through to SQL extraction
                }
            }
        }
        return extractFencedSql(reply);
    }

    /**
     * Tolerant fallback for models that answer with a bare SQL statement
     * instead of the JSON contract: accepts a ```sql fenced block, or a
     * reply that starts with SELECT/WITH. Whatever is extracted still goes
     * through the full server-side validation (read-only rules, table
     * scoping), so this cannot weaken safety.
     */
    static QueryAction extractFencedSql(String reply) {
        if (reply == null) {
            return null;
        }
        java.util.regex.Matcher fenced = java.util.regex.Pattern
                .compile("(?is)```(?:sql)?\\s*(.+?)```").matcher(reply);
        String candidate = null;
        if (fenced.find()) {
            candidate = fenced.group(1).trim();
        } else {
            String trimmed = reply.trim();
            if (trimmed.matches("(?is)(SELECT|WITH)\\b.*")) {
                candidate = trimmed;
            }
        }
        if (candidate == null
                || !candidate.matches("(?is)(SELECT|WITH)\\b.*")) {
            return null;
        }
        // One optional trailing semicolon; anything more is not a single
        // statement and will be rejected downstream anyway.
        if (candidate.endsWith(";")) {
            candidate = candidate.substring(0, candidate.length() - 1).trim();
        }
        if (candidate.contains(";") || candidate.length() > 4000) {
            return null;
        }
        return new QueryAction(candidate);
    }

    private static String stripFences(String text) {
        return text.replaceAll("(?s)```[a-zA-Z]*", "").replace("```", "");
    }

    /** First balanced {...} starting at start, or null. */
    private static String balancedObject(String text, int start) {
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    inString = false;
                }
            } else if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private ChatConversation getOrCreateConversation(Project project) {
        return conversationRepository.findByProjectId(project.getId())
                .orElseGet(() -> conversationRepository.save(
                        ChatConversation.builder().project(project).build()));
    }

    private ChatMessage persist(ChatConversation conversation, ChatMessage.ChatRole role,
            String content) {
        return persist(conversation, role, content, null);
    }

    private ChatMessage persist(ChatConversation conversation, ChatMessage.ChatRole role,
            String content, String usedSql) {
        return messageRepository.save(ChatMessage.builder()
                .conversation(conversation)
                .role(role)
                .content(content)
                .usedSql(usedSql)
                .build());
    }

    private OllamaMessage toOllama(ChatMessage message) {
        return new OllamaMessage(
                message.getRole() == ChatMessage.ChatRole.USER ? "user" : "assistant",
                message.getContent());
    }

    private static ChatDtos.ChatMessageResponse toResponse(ChatMessage message) {
        return new ChatDtos.ChatMessageResponse(
                message.getId(),
                message.getRole().name().toLowerCase(),
                message.getContent(),
                message.getUsedSql());
    }
}
