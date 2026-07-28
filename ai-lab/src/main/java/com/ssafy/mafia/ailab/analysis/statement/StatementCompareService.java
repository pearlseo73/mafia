package com.ssafy.mafia.ailab.analysis.statement;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.ssafy.mafia.ailab.analysis.statement.StatementCompareResult.Contradiction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 진술대조권 (`STATEMENT_COMPARE`) — 지목한 플레이어가 <b>전체 라운드에 걸쳐</b> 한 말에서
 * 서로 앞뒤가 맞지 않는 발언을 찾아낸다.
 *
 * <p>키워드 뽑기와 다른 점 세 가지.
 * <ol>
 *   <li><b>라운드를 제한하지 않는다.</b> 라운드 간 말이 바뀌는 것이 이 아이템의 핵심이다 (§7-1)
 *   <li><b>발화에 번호를 매겨서 넣는다.</b> 그래서 렌더링·검증·치환이 모두 이 클래스에 있다
 *   <li><b>모델이 {@code gpt-5.4-mini} 다.</b> "의견 변경"과 "과거 진술 왜곡"을 구분하는
 *       추론이 판정의 본체다 → docs/LLM호출설정.md §4
 * </ol>
 *
 * <p><b>환각 방어를 서버가 한다.</b> LLM 이 없는 발화 번호를 지목해도
 * {@link #dropUnknownIds}에서 버려진다. 프롬프트 품질과 무관하게 보장된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatementCompareService {

    /**
     * 이 미달이면 LLM 을 호출하지 않는다 (AI기능-구현설계 §7-5).
     *
     * <p>값은 키워드 뽑기와 같지만 상수를 공유하지 않는다. 진술대조권은 전체 라운드를 보므로
     * 훨씬 덜 걸리고, 기능별로 임계값이 갈릴 여지가 있다 (결정필요사항 T8).
     */
    private static final int MIN_UTTERANCES = 3;
    private static final int MIN_CHARS = 40;

    /** 판정이 본체라 추론 모델을 쓴다. {@code gpt-5.x} 는 max_tokens 를 쓰면 HTTP 400 이다. */
    private static final String MODEL = "gpt-5.4-mini";
    private static final int MAX_COMPLETION_TOKENS = 2048;

    private static final Resource SYSTEM_PROMPT =
            new ClassPathResource("prompts/statement-compare-system.st");
    private static final Resource USER_PROMPT =
            new ClassPathResource("prompts/statement-compare-user.st");

    private final ChatClient chatClient;

    /**
     * @param targetName 지목 대상
     * @param statements 대상의 공개 발화. <b>전체 라운드</b>, 아이템 사용 시각 이전, 시간순
     */
    public StatementCompareResult compare(String targetName, List<Statement> statements) {
        if (!hasEnoughToAnalyze(statements)) {
            log.debug("발화 부족 → LLM 호출 생략. target={} 건수={}",
                    targetName, statements == null ? 0 : statements.size());
            return StatementCompareResult.insufficient();
        }

        try {
            StatementCompareResult raw = chatClient.prompt()
                    // Spring AI 2.0 의 .options() 는 Builder 를 받는다 (1.x 는 ChatOptions).
                    .options(OpenAiChatOptions.builder()
                            .model(MODEL)
                            .maxCompletionTokens(MAX_COMPLETION_TOKENS))
                    .system(s -> s.text(SYSTEM_PROMPT, StandardCharsets.UTF_8))
                    .user(u -> u.text(USER_PROMPT, StandardCharsets.UTF_8)
                            .param("targetName", targetName)
                            .param("utterances", render(statements)))
                    .call()
                    .entity(StatementCompareResult.class);

            return raw == null
                    ? StatementCompareResult.unavailable()
                    : verify(raw, statements.size());
        } catch (Exception e) {
            // 키 오류·네트워크·파싱 실패를 한 곳에서 흡수한다. 토론 중 호출이라 게임이 멈추면 안 된다.
            log.warn("[statement] 대조 실패 → 폴백. target={} : {}", targetName, e.toString(), e);
            return StatementCompareResult.unavailable();
        }
    }

    /** 발화가 분석할 만큼 있는지. 미달이면 "분석할 발언이 부족합니다"로 즉시 응답한다. */
    public static boolean hasEnoughToAnalyze(List<Statement> statements) {
        if (statements == null || statements.size() < MIN_UTTERANCES) {
            return false;
        }
        return statements.stream().mapToInt(s -> s.text().length()).sum() >= MIN_CHARS;
    }

    /**
     * 프롬프트에 들어갈 발화 목록. {@code u1 [R1] 발화내용} 형태다.
     *
     * <p><b>라운드 표시가 필수다.</b> 라운드가 없으면 "저는 처음부터 강예린 씨를 믿었습니다"가
     * <i>과거에 대한 주장</i>임을 알 수 없어서 "직접 충돌하지 않는다"로 판정된다 (실측 확인).
     *
     * <p>시각은 넣지 않는다. 라운드만 있으면 판정에 충분하고 초 단위 시각은 토큰만 쓴다.
     * 번호로 원문을 되돌릴 수 있으므로 화면 표시에는 문제없다.
     */
    public static String render(List<Statement> statements) {
        return IntStream.range(0, statements.size())
                .mapToObj(i -> "%s [R%d] %s".formatted(
                        idAt(i), statements.get(i).round(), statements.get(i).text()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    /** 0-based 인덱스 → 발화 번호. 번호는 1부터 시작한다. */
    public static String idAt(int index) {
        return "u" + (index + 1);
    }

    /**
     * 발화 번호를 원문으로 치환한다 — 화면에 나갈 인용문은 <b>서버가 조립</b>한다.
     * 범위 밖 번호는 조용히 건너뛴다 (AI-API명세 §2-5 의 {@code quote} 가 이 결과다).
     */
    public static List<Statement> resolve(List<String> utteranceIds, List<Statement> statements) {
        return utteranceIds.stream()
                .map(id -> indexOf(id, statements.size()))
                .filter(i -> i >= 0)
                .map(statements::get)
                .toList();
    }

    /**
     * LLM 이 지목한 번호를 검증한다 — 진술대조권의 환각 방어.
     *
     * <p>{@code u99} 처럼 없는 번호는 버린다. 번호가 하나만 남은 묶음도 버린다 — 모순은
     * 최소 두 발언 사이에서만 성립하므로, 남은 하나로는 아무것도 보여줄 수 없다.
     */
    private StatementCompareResult verify(StatementCompareResult raw, int size) {
        if (!raw.hasFindings()) {
            // 모순 없음을 LLM 이 정직하게 답한 경우. 문구는 서버가 고르므로 enum 만 확정한다.
            return StatementCompareResult.noContradiction();
        }
        return raw.withContradictions(dropDuplicates(dropUnknownIds(raw, size)));
    }

    /**
     * 같은 발화 묶음을 두 번 내지 않는다.
     *
     * <p>실측에서 {@code [u2, u6]} 이 거의 같은 reason 으로 두 번 나왔다. 프롬프트로도
     * 억제하지만(규칙 6) 화면에 같은 인용문이 두 번 뜨는 것은 서버가 확실히 막는다.
     *
     * <p>겹치되 다른 묶음({@code [u2,u6]} 과 {@code [u2,u9]})은 남긴다 — 3단 모순이
     * 짝으로 쪼개진 정상적인 형태다 (기대답안 볼 포인트 1).
     */
    private List<Contradiction> dropDuplicates(List<Contradiction> contradictions) {
        Set<Set<String>> seen = new HashSet<>();
        List<Contradiction> kept = contradictions.stream()
                .filter(c -> seen.add(new HashSet<>(c.idsOrEmpty())))
                .toList();

        if (kept.size() < contradictions.size()) {
            log.debug("[statement] 중복 묶음 {}개 제거", contradictions.size() - kept.size());
        }
        return kept;
    }

    private List<Contradiction> dropUnknownIds(StatementCompareResult raw, int size) {
        List<Contradiction> kept = raw.contradictionsOrEmpty().stream()
                .map(c -> new Contradiction(
                        c.idsOrEmpty().stream()
                                .filter(id -> indexOf(id, size) >= 0)
                                .distinct()
                                .toList(),
                        c.reason()))
                .filter(c -> c.idsOrEmpty().size() >= 2)
                .toList();

        if (kept.size() < raw.contradictionsOrEmpty().size()) {
            log.warn("[statement] 발화 번호 검증에서 {}개 묶음 제거 (없는 번호 또는 1개만 남음). 원본={}",
                    raw.contradictionsOrEmpty().size() - kept.size(), raw.allUtteranceIds());
        }
        return kept;
    }

    /** {@code "u2"} → 1. 형식이 틀리거나 범위를 벗어나면 -1. */
    private static int indexOf(String utteranceId, int size) {
        if (utteranceId == null || !utteranceId.matches("u\\d+")) {
            return -1;
        }
        int index = Integer.parseInt(utteranceId.substring(1)) - 1;
        return (index >= 0 && index < size) ? index : -1;
    }
}
