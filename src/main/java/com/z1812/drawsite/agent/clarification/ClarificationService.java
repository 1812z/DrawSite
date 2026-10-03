package com.z1812.drawsite.agent.clarification;

import com.z1812.drawsite.agent.runtime.AgentRunMapper;
import com.z1812.drawsite.application.ApplicationMapper;
import com.z1812.drawsite.conversation.ConversationService;
import com.z1812.drawsite.common.exception.BusinessException;
import com.z1812.drawsite.tool.framework.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import static com.z1812.drawsite.agent.clarification.ClarificationDtos.*;

@Service
public class ClarificationService {
    private final ClarificationMapper mapper;
    private final AgentRunMapper runs;
    private final ApplicationMapper applications;
    private final ConversationService conversations;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ConcurrentMap<String, CompletableFuture<List<Answer>>> waiting = new ConcurrentHashMap<>();

    public ClarificationService(ClarificationMapper mapper, AgentRunMapper runs, ApplicationMapper applications,
                                ConversationService conversations, TransactionTemplate transactions) {
        this.mapper = mapper; this.runs = runs; this.applications = applications;
        this.conversations = conversations; this.transactions = transactions;
    }

    public DeferredToolResult ask(ToolContext context, List<Question> questions) {
        validateQuestions(questions);
        var run = runs.selectOneById(context.runId());
        var item = new ClarificationEntity();
        item.setId(UUID.randomUUID().toString()); item.setRunId(run.getId()); item.setConversationId(run.getConversationId());
        item.setQuestionsJson(json.writeValueAsString(questions)); item.setStatus("PENDING"); item.setCreatedAt(OffsetDateTime.now());
        mapper.insert(item);
        return bound -> {
            var future = new CompletableFuture<List<Answer>>();
            waiting.put(item.getId(), future);
            bound.waitingForUser().accept(true);
            try {
                while (!bound.stopped().getAsBoolean()) {
                    var stored = mapper.selectOneById(item.getId());
                    if (bound.stopped().getAsBoolean()) throw new IllegalStateException("任务已停止");
                    if (stored == null || "CANCELLED".equals(stored.getStatus())) throw new IllegalStateException("询问已停止");
                    if ("ANSWERED".equals(stored.getStatus())) return Map.of("questions", questions, "answers", decodeAnswers(stored.getAnswersJson()));
                    try { return Map.of("questions", questions, "answers", future.get(1, TimeUnit.SECONDS)); }
                    catch (TimeoutException ignored) { }
                }
                throw new IllegalStateException("任务已停止");
            } finally {
                waiting.remove(item.getId(), future);
                mapper.cancel(context.runId());
                bound.waitingForUser().accept(false);
            }
        };
    }

    public static void validateQuestions(List<Question> questions) {
        if (questions == null || questions.isEmpty() || questions.size() > 3) throw new IllegalArgumentException("一次必须提供1至3个问题");
        for (var question : questions) {
            if (question == null || question.question() == null || question.question().isBlank() || question.question().length() > 1000
                    || question.options() == null || question.options().size() > 5
                    || question.options().stream().anyMatch(option -> option == null || option.isBlank() || option.length() > 200)
                    || new HashSet<>(question.options()).size() != question.options().size()) {
                throw new IllegalArgumentException("问题需包含有效文本及0至5个不重复候选项");
            }
        }
    }

    public static void validateAnswers(List<Question> questions, List<Answer> answers) {
        if (answers == null || answers.size() != questions.size()) throw new IllegalArgumentException("必须一次回答所有问题");
        var indexes = new HashSet<Integer>();
        for (var answer : answers) {
            if (answer == null || answer.questionIndex() < 0 || answer.questionIndex() >= questions.size() || !indexes.add(answer.questionIndex())
                    || answer.selectedOptions() == null || answer.selectedOptions().size() > 5
                    || new HashSet<>(answer.selectedOptions()).size() != answer.selectedOptions().size()
                    || !questions.get(answer.questionIndex()).options().containsAll(answer.selectedOptions())
                    || (answer.customText() != null && answer.customText().length() > 4000)
                    || (answer.selectedOptions().isEmpty() && (answer.customText() == null || answer.customText().isBlank()))) {
                throw new IllegalArgumentException("每个问题必须选择候选项或填写自定义需求，且不能重复回答");
            }
        }
    }

    public List<View> list(String appId, String conversationId) {
        conversations.requireOwned(appId, conversationId);
        return mapper.list(conversationId).stream().map(this::view).toList();
    }
    public View pending(String runId) {
        var item = mapper.pending(runId);
        return item == null ? null : view(item);
    }
    public String history(String conversationId) {
        var items = new ArrayList<>(mapper.answered(conversationId));
        Collections.reverse(items);
        var result = new StringBuilder();
        for (var item : items) {
            String text = "\n问题：" + item.getQuestionsJson() + "\n用户回答：" + item.getAnswersJson();
            if (result.length() + text.length() > 16000) break;
            result.append(text);
        }
        return result.toString();
    }
    public View reply(String appId, String conversationId, String id, Reply reply) {
        conversations.requireOwned(appId, conversationId);
        var result = transactions.execute(status -> {
            applications.lockById(appId);
            var item = mapper.selectOneById(id);
            if (item == null || !conversationId.equals(item.getConversationId())) throw new BusinessException("QUESTION_NOT_FOUND", "询问不存在", HttpStatus.NOT_FOUND);
            var run = runs.selectOneById(item.getRunId());
            if (!"PENDING".equals(item.getStatus()) || run == null || !"RUNNING".equals(run.getStatus())) {
                throw new BusinessException("QUESTION_NOT_PENDING", "询问已回答或任务已停止", HttpStatus.CONFLICT);
            }
            try { validateAnswers(decodeQuestions(item.getQuestionsJson()), reply.answers()); }
            catch (IllegalArgumentException exception) { throw new BusinessException("INVALID_ANSWERS", exception.getMessage(), HttpStatus.BAD_REQUEST); }
            var answers = reply.answers().stream().sorted(Comparator.comparingInt(Answer::questionIndex))
                    .map(answer -> new Answer(answer.questionIndex(), List.copyOf(answer.selectedOptions()), answer.customText() == null ? "" : answer.customText().strip())).toList();
            if (mapper.answer(id, json.writeValueAsString(answers)) != 1) throw new BusinessException("QUESTION_NOT_PENDING", "询问已回答", HttpStatus.CONFLICT);
            return view(mapper.selectOneById(id));
        });
        var future = waiting.get(id);
        if (future != null) future.complete(result.answers());
        return result;
    }
    public void cancel(String runId) { mapper.cancel(runId); }
    public void recover() { mapper.recover(); }
    private List<Question> decodeQuestions(String value) { return json.readValue(value, json.getTypeFactory().constructCollectionType(List.class, Question.class)); }
    private List<Answer> decodeAnswers(String value) { return value == null ? List.of() : json.readValue(value, json.getTypeFactory().constructCollectionType(List.class, Answer.class)); }
    private View view(ClarificationEntity item) {
        return new View(item.getId(), item.getRunId(), item.getConversationId(), decodeQuestions(item.getQuestionsJson()),
                decodeAnswers(item.getAnswersJson()), item.getStatus(), item.getCreatedAt(), item.getAnsweredAt());
    }
}
