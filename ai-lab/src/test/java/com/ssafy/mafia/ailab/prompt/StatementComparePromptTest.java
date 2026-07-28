package com.ssafy.mafia.ailab.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.ssafy.mafia.ailab.analysis.statement.Statement;
import com.ssafy.mafia.ailab.analysis.statement.StatementCompareResult;
import com.ssafy.mafia.ailab.analysis.statement.StatementCompareService;
import com.ssafy.mafia.ailab.fixture.Utterance;
import com.ssafy.mafia.ailab.fixture.UtteranceLog;

/**
 * 진술대조권 프롬프트 검증.
 *
 * <p>기대답안은 문자열 일치 검증용이 아니다 — 판정 품질은 <b>콘솔의 "볼 포인트" 줄을 눈으로</b>
 * 본다. 다만 아래 셋은 눈으로 놓치므로 자동 검사로 둔다.
 * <ul>
 *   <li>범위 밖 발화 번호가 남지 않았는지 (환각 방어) — 서비스가 이미 버리지만 결과를 확인한다
 *   <li>{@code u5}("마음이 바뀐 겁니다")를 모순으로 잡지 않았는지 — 이 프롬프트의 핵심 실패 조건
 *   <li>밤 로그 문구가 섞이지 않았는지 (마피아 신원 유출)
 * </ul>
 *
 * <p>실행: {@code ./gradlew test --tests "*StatementComparePromptTest*"}
 *
 * <p>프롬프트: {@code src/main/resources/prompts/statement-compare-*.st}
 * <br>기대답안: {@code log_analysis/expected/진술대조-기대답안.md}
 */
@SpringBootTest
class StatementComparePromptTest {

    @Autowired
    StatementCompareService statementCompareService;

    // ==================================================================
    // TC-1 · TC-2 — 박도현 3단 모순 (핵심 케이스, 픽스처의 실제 사용 기록)
    // 김서준이 R3 토론 21:11:05 에 박도현을 지목한다.
    // ==================================================================
    @Test
    @DisplayName("TC-1·2 박도현 3단 모순 (u2·u6·u9) 과 자기모순 (u8·u9)")
    void TC1_TC2_박도현_3단_모순() {
        List<Statement> input = statementsOf("박도현", "21:11:05");
        print("TC-1·2 입력", input);

        // u10(21:11:35)부터는 아이템을 쓴 시점에 아직 존재하지 않았다.
        assertThat(input).as("사용 시각 이전 전체 라운드 발화 9건").hasSize(9);
        assertThat(input).as("라운드를 제한하지 않는 것이 키워드 뽑기와의 차별점")
                .anyMatch(s -> s.round() == 1)
                .anyMatch(s -> s.round() == 2)
                .anyMatch(s -> s.round() == 3);

        StatementCompareResult result = compare("박도현", input);
        print("TC-1·2 출력", result, input);

        List<String> ids = result.allUtteranceIds();
        checkpoint(result.hasFindings(), "모순을 찾았는가");
        checkpoint(ids.containsAll(List.of("u2", "u6", "u9")),
                "3단 모순 u2·u6·u9 가 다 등장하는가 (묶음이 몇 개로 쪼개졌든 무관)");
        checkpoint(ids.containsAll(List.of("u8", "u9")), "같은 라운드 자기모순 u8·u9 를 잡았는가");
        checkpoint(summaryLength(result) <= 80,
                "summary 가 80자 이내인가 (%d자)".formatted(summaryLength(result)));
        checkpoint(!mentionsRole(result), "reason·summary 에 역할 추정이 섞이지 않았는가");

        assertThat(result.hasFindings()).as("박도현에게는 분명한 모순이 있다").isTrue();
        assertThat(ids)
                .as("범위 밖 번호가 남으면 번호 방식을 쓴 이유가 무너진다")
                .allMatch(id -> validIds(input.size()).contains(id));
        assertThat(ids)
                .as("u5 '마음이 바뀐 겁니다'는 의견 변경 해명이지 모순이 아니다 "
                        + "— 잡았다면 System 규칙 4를 강화한다")
                .doesNotContain("u5");
        UtteranceLog.assertNoNightLogLeak(allText(result));
    }

    // ==================================================================
    // TC-5 — 모순 없음 (없는 모순을 만들어내지 않는가)
    // 정민재의 미사용 아이템으로 R3 에 김서준을 지목한 가정 케이스.
    // ==================================================================
    @Test
    @DisplayName("TC-5 김서준은 모순이 없다 — 새 근거로 판단이 바뀐 것을 잡으면 실패")
    void TC5_모순_없음() {
        List<Statement> input = statementsOf("김서준", "21:11:05");
        print("TC-5 입력", input);

        StatementCompareResult result = compare("김서준", input);
        print("TC-5 출력", result, input);

        checkpoint(!result.hasFindings(),
                "모순 없음으로 답했는가 (R1 강예린 의심 → R3 박도현 지목은 새 근거로 바뀐 것이다)");
        checkpoint("NO_CONTRADICTION".equals(result.noFindingReason()),
                "noFindingReason 이 NO_CONTRADICTION 인가");

        assertThat(result.allUtteranceIds())
                .allMatch(id -> validIds(input.size()).contains(id));
        UtteranceLog.assertNoNightLogLeak(allText(result));
    }

    // ==================================================================
    // STT 실측 — 같은 대본을 사람이 브라우저에 읽어서 받은 입력으로 TC-1·2 재현
    //
    // 판정 품질에 어서션을 걸지 않는다. 나빠지는 것을 보려고 돌리는 테스트이고,
    // 나빠졌다고 빌드를 깨면 실측값을 기록할 수 없다. 볼 포인트로만 찍는다.
    // ==================================================================
    @Test
    @DisplayName("STT 실측 입력으로 돌리면 무엇이 살아남는가")
    void STT실측_입력() {
        List<Statement> input = UtteranceLog.statements(UtteranceLog.loadSttReal());
        print("STT 실측 입력", input);

        // 원본 9건이 8건으로 왔다 (대본 6·7 이 한 건으로 합쳐졌다).
        assertThat(input).as("STT 가 돌려준 건수").hasSize(8);

        StatementCompareResult result = compare("박도현", input);
        print("STT 실측 출력", result, input);

        List<String> ids = result.allUtteranceIds();
        // 번호가 한 칸씩 밀렸다. 깨끗한 입력의 u2·u6·u9 가 여기서는 u2·u6·u8 이다.
        checkpoint(ids.containsAll(List.of("u2", "u6", "u8")),
                "3단 모순 u2·u6·u8 을 이름이 흔들려도 잡는가 (강예린/강혜린/강예은)");
        checkpoint(ids.containsAll(List.of("u7", "u8")),
                "u7·u8 자기모순이 남았는가 — '찬성했습니다'가 '편성했습니다'로 인식됐다");
        checkpoint(!ids.contains("u5"), "u5 '마음이 바뀐 겁니다'를 잡지 않았는가");
        checkpoint(!ids.contains("u3") && !ids.contains("u4"),
                "u3·u4 를 잡지 않았는가 — '방어권'이 '방학과'가 되어 뜻이 뒤틀린 발화다");

        assertThat(ids).allMatch(id -> validIds(input.size()).contains(id));
        UtteranceLog.assertNoNightLogLeak(allText(result));
    }

    // ==================================================================
    // TC-4 — 발화 부족 (LLM 을 호출하지 않는다)
    // ==================================================================
    @Test
    @DisplayName("TC-4 최지우는 발화가 부족해 LLM 을 호출하지 않는다")
    void TC4_발화_부족() {
        List<Statement> input = statementsOf("최지우", "21:03:00");
        print("TC-4 입력", input);

        assertThat(StatementCompareService.hasEnoughToAnalyze(input))
                .as("발화 2건 · 약 25자 → 임계값(3건·40자) 미달")
                .isFalse();

        StatementCompareResult result = compare("최지우", input);
        print("TC-4 출력", result, input);

        // 호출하지 않으면 억지 모순 생성이 애초에 발생할 수 없다 (체크리스트의 최대 실패 모드).
        assertThat(result.hasFindings()).isFalse();
        assertThat(result.noFindingReason()).isEqualTo("INSUFFICIENT");
        assertThat(result.contradictionsOrEmpty()).isEmpty();
    }

    // ==================================================================
    // 호출 · 검증 · 출력
    // ==================================================================
    private List<Statement> statementsOf(String target, String usedAt) {
        List<Utterance> raw = UtteranceLog.forStatementCompare(target, usedAt);
        return UtteranceLog.statements(raw);
    }

    private StatementCompareResult compare(String target, List<Statement> input) {
        long t = System.currentTimeMillis();
        StatementCompareResult result = statementCompareService.compare(target, input);
        System.out.printf("  (%dms)%n", System.currentTimeMillis() - t);
        return result;
    }

    private static List<String> validIds(int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(StatementCompareService::idAt)
                .toList();
    }

    private static int summaryLength(StatementCompareResult r) {
        return r.summary() == null ? 0 : r.summary().length();
    }

    /** 규칙 5 위반 검사. 눈으로 볼 지표라 어서션이 아니라 볼 포인트로만 쓴다. */
    private static boolean mentionsRole(StatementCompareResult r) {
        String text = allText(r);
        return text.contains("마피아") || text.contains("시민");
    }

    private static String allText(StatementCompareResult r) {
        StringBuilder sb = new StringBuilder(r.summary() == null ? "" : r.summary());
        r.contradictionsOrEmpty().forEach(c -> sb.append(' ').append(c.reason()));
        return sb.toString();
    }

    private void print(String label, List<Statement> input) {
        System.out.println("\n=== " + label + " (" + input.size() + "건) ===");
        System.out.println(StatementCompareService.render(input));
    }

    /** 발화 번호를 원문으로 치환한 결과까지 찍는다 — 프론트가 받는 인용문이 이것이다. */
    private void print(String label, StatementCompareResult r, List<Statement> input) {
        System.out.println("--- " + label + " ---");
        System.out.println("  hasFindings     : " + r.hasFindings());
        System.out.println("  noFindingReason : " + r.noFindingReason());
        System.out.println("  summary         : " + r.summary());
        r.contradictionsOrEmpty().forEach(c -> {
            System.out.println("  · " + c.idsOrEmpty() + " " + c.reason());
            StatementCompareService.resolve(c.idsOrEmpty(), input).forEach(s ->
                    System.out.printf("      [R%d %s] %s%n", s.round(), s.time(), s.text()));
        });
    }

    /** 기대답안의 "볼 포인트". 실패해도 테스트를 깨지 않고 눈으로 보게 한다. */
    private static void checkpoint(boolean ok, String what) {
        System.out.println("  " + (ok ? "[O]" : "[X]") + " " + what);
    }
}
