package com.ssafy.mafia.ailab.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;

import com.ssafy.mafia.ailab.analysis.keyword.KeywordResult;
import com.ssafy.mafia.ailab.fixture.Utterance;
import com.ssafy.mafia.ailab.fixture.UtteranceLog;

/**
 * 키워드 뽑기 프롬프트 검증.
 *
 * <p>기대답안은 문자열 일치 검증용이 아니다 — 형식과 판정을 <b>눈으로</b> 본다.
 * 다만 아래 두 가지는 눈으로 놓치므로 자동 검사로 둔다.
 * <ul>
 *   <li>키워드가 입력 발화의 부분 문자열인지 (환각 방어)
 *   <li>밤 로그 문구가 섞이지 않았는지 (마피아 신원 유출)
 * </ul>
 *
 * <p>실행: {@code ./gradlew test --tests "*KeywordExtractPromptTest*"}
 *
 * <p>프롬프트: {@code src/main/resources/prompts/keyword-extract-*.st}
 * <br>기대답안: {@code log_analysis/expected/키워드뽑기-기대답안.md}
 */
@SpringBootTest
class KeywordExtractPromptTest {

    @Autowired
    ChatClient.Builder chatClientBuilder;

    @Value("classpath:/prompts/keyword-extract-system.st")
    Resource systemPrompt;

    @Value("classpath:/prompts/keyword-extract-user.st")
    Resource userPrompt;

    // ==================================================================
    // TC-8 — 강예린 R1 (핵심 케이스, 픽스처의 실제 사용 기록)
    // ==================================================================
    @Test
    @DisplayName("TC-8 강예린 R1 키워드 추출")
    void TC8_강예린_R1() {
        List<Utterance> input = UtteranceLog.forKeywordExtract("강예린", 1, "21:02:09");
        print("TC-8 입력", input);

        assertThat(input).as("사용 시각 이전 R1 발화 4건").hasSize(4);
        assertThat(UtteranceLog.hasEnoughToAnalyze(input)).isTrue();

        KeywordResult result = extract("강예린", 1, input);
        print("TC-8 출력", result);

        assertThat(result.hasFindings()).isTrue();
        assertThat(result.keywordsOrEmpty()).hasSizeBetween(3, 5);
        assertVerbatim(result, input);
        UtteranceLog.assertNoNightLogLeak(String.join(" ", result.keywordsOrEmpty()));
    }

    // ==================================================================
    // TC-10 — 라운드 범위 제한 (진술대조권과의 차별점)
    // ==================================================================
    @Test
    @DisplayName("TC-10 R2 에서 쓰면 R1 키워드가 섞이지 않는다")
    void TC10_라운드_범위() {
        List<Utterance> input = UtteranceLog.forKeywordExtract("강예린", 2, "21:07:30");
        print("TC-10 입력 (R2 만)", input);

        assertThat(input).allSatisfy(u -> assertThat(u.round()).isEqualTo(2));

        KeywordResult result = extract("강예린", 2, input);
        print("TC-10 출력", result);

        assertVerbatim(result, input);

        // R1 에만 있는 표현이 나오면 조회 범위가 잘못 잡힌 것이다.
        // 프롬프트 문제가 아니라 입력을 만드는 코드의 문제다.
        assertThat(result.keywordsOrEmpty())
                .as("R1 발화에서 온 키워드가 섞이면 진술대조권과 산출물이 겹친다")
                .noneMatch(k -> k.contains("시간 낭비") || k.contains("느낌") || k.contains("억울"));
    }

    // ==================================================================
    // TC-9 — 발화 부족 (LLM 을 호출하지 않는다)
    // ==================================================================
    @Test
    @DisplayName("TC-9 최지우는 발화가 부족해 LLM 을 호출하지 않는다")
    void TC9_발화_부족() {
        List<Utterance> input = UtteranceLog.forKeywordExtract("최지우", 1, "21:03:00");
        print("TC-9 입력", input);

        assertThat(UtteranceLog.hasEnoughToAnalyze(input))
                .as("발화 2건 · 약 25자 → 임계값(3건·40자) 미달")
                .isFalse();

        KeywordResult result = UtteranceLog.hasEnoughToAnalyze(input)
                ? extract("최지우", 1, input)
                : KeywordResult.insufficient();   // 서버가 만든다. LLM 호출 없음
        print("TC-9 출력", result);

        assertThat(result.hasFindings()).isFalse();
        assertThat(result.noFindingReason()).isEqualTo("INSUFFICIENT");
    }

    // ==================================================================
    // 호출 · 검증 · 출력
    // ==================================================================
    private KeywordResult extract(String target, int round, List<Utterance> input) {
        long t = System.currentTimeMillis();
        KeywordResult result = chatClientBuilder.build()
                .prompt()
                .system(s -> s.text(systemPrompt, StandardCharsets.UTF_8))
                .user(u -> u.text(userPrompt, StandardCharsets.UTF_8)
                        .param("targetName", target)
                        .param("round", round)
                        .param("utterances", UtteranceLog.toPlainLines(input)))
                .call()
                .entity(KeywordResult.class);
        System.out.printf("  (%dms)%n", System.currentTimeMillis() - t);
        return result;
    }

    /**
     * 환각 방어 — 각 키워드가 입력에 실제로 등장하는 연속 문자열인지.
     * 공백을 제거하고 비교하므로 "시간낭비" 도 "시간 낭비" 에 매칭된다.
     * 조사·어미를 뗀 "억울" 은 "억울하면" 의 부분 문자열이라 통과한다.
     */
    private void assertVerbatim(KeywordResult result, List<Utterance> input) {
        String haystack = UtteranceLog.toPlainLines(input).replaceAll("\\s", "");
        for (String keyword : result.keywordsOrEmpty()) {
            String needle = keyword.replaceAll("\\s", "");
            if (!haystack.contains(needle)) {
                System.out.println("  ❌ 원문에 없는 키워드: " + keyword);
            }
        }
        assertThat(result.keywordsOrEmpty())
                .as("로그에 없는 표현을 만들어내면 안 된다 (상위 개념어 포함)")
                .allMatch(k -> haystack.contains(k.replaceAll("\\s", "")));
    }

    private void print(String label, List<Utterance> utterances) {
        System.out.println("\n=== " + label + " (" + utterances.size() + "건) ===");
        utterances.forEach(u -> System.out.println("  " + u));
    }

    private void print(String label, KeywordResult r) {
        System.out.println("--- " + label + " ---");
        System.out.println("  hasFindings     : " + r.hasFindings());
        System.out.println("  noFindingReason : " + r.noFindingReason());
        System.out.println("  keywords        : " + r.keywordsOrEmpty());
    }
}
