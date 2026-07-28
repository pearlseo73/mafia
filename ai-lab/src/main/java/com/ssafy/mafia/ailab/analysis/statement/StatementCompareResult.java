package com.ssafy.mafia.ailab.analysis.statement;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 진술대조권의 <b>LLM 출력 계약</b>. Spring AI 가 이 타입으로 JSON Schema 를 만들어 형식을
 * 강제하고 {@code .call().entity(...)} 로 역직렬화한다. 필드명과
 * {@link JsonPropertyDescription} 이 스키마에 실려 <b>프롬프트의 일부가 된다</b>
 * → docs/LLM호출설정.md §7
 *
 * <p><b>발화 원문이 없다.</b> LLM 은 발화 번호({@code u2})만 반환하고 서버가 원문으로
 * 치환한다. 원문을 생성하지 않으면 없는 발언을 만들어낼 수 없다 — 이것이 진술대조권의
 * 환각 방어다 (키워드 뽑기는 부분 문자열 검사로, 방식이 다르다).
 *
 * <p>{@code requestId}·{@code itemCode}·{@code targetPlayerId} 는 서버가 아는 값이라
 * 여기에 없다. 서버가 이 결과를 감싸서 내보낸다 (AI-API명세 §2-5).
 *
 * @param hasFindings     모순을 찾았는지. false 는 에러가 아니라 정상 응답이다
 * @param noFindingReason hasFindings=false 일 때만. NO_CONTRADICTION | INSUFFICIENT |
 *                        AI_UNAVAILABLE. 화면 문구는 서버가 이 값으로 고른다 —
 *                        LLM 문구를 그대로 쓰면 매번 표현이 달라진다
 * @param summary         한 문장. 무엇과 무엇이 충돌하는지만
 * @param contradictions  모순 묶음. 1개든 3개로 쪼개졌든 상관없다
 */
public record StatementCompareResult(
        boolean hasFindings,

        String noFindingReason,

        @JsonPropertyDescription("무엇과 무엇이 충돌하는지 한 문장으로. 80자 이내")
        String summary,

        List<Contradiction> contradictions) {

    /**
     * 서로 충돌하는 발화 한 묶음.
     *
     * @param utteranceIds 충돌하는 발화 번호 2개 이상. 원문이 아니라 번호다
     * @param reason       왜 충돌하는지 한 문장
     */
    public record Contradiction(
            @JsonPropertyDescription("서로 충돌하는 발언 번호 2개 이상. u1, u2 같은 형태로만. "
                    + "발언 원문은 쓰지 않는다. reason 에서 언급한 번호는 하나도 빠뜨리지 않는다")
            List<String> utteranceIds,

            @JsonPropertyDescription("왜 서로 양립하지 않는지 한 문장. "
                    + "여기서 언급하는 발언 번호는 모두 utteranceIds 에 들어 있어야 한다. "
                    + "누가 마피아인지는 추측하지 않는다")
            String reason) {

        public List<String> idsOrEmpty() {
            return utteranceIds == null ? List.of() : utteranceIds;
        }
    }

    /** 발화가 임계값 미달 — LLM 을 호출하지 않고 서버가 만든다 (AI기능-구현설계 §7-5). */
    public static StatementCompareResult insufficient() {
        return new StatementCompareResult(false, "INSUFFICIENT", null, List.of());
    }

    /** 모순 없음. 억지로 만들어내지 않은 정상적인 결과다. */
    public static StatementCompareResult noContradiction() {
        return new StatementCompareResult(false, "NO_CONTRADICTION", null, List.of());
    }

    /** LLM 호출 실패 — AI-API명세 §3 의 {@code AI_UNAVAILABLE} 에 대응한다. */
    public static StatementCompareResult unavailable() {
        return new StatementCompareResult(false, "AI_UNAVAILABLE", null, List.of());
    }

    public List<Contradiction> contradictionsOrEmpty() {
        return contradictions == null ? List.of() : contradictions;
    }

    /** 이 결과가 지목한 모든 발화 번호. 검증·표시에 쓴다. */
    public List<String> allUtteranceIds() {
        return contradictionsOrEmpty().stream()
                .flatMap(c -> c.idsOrEmpty().stream())
                .distinct()
                .toList();
    }

    /**
     * 서버가 번호를 검증한 뒤 살아남은 묶음만 갈아끼운다.
     * 전부 버려졌으면 확인된 모순이 없는 것이므로 "모순 없음"으로 내린다.
     */
    public StatementCompareResult withContradictions(List<Contradiction> verified) {
        return verified.isEmpty()
                ? noContradiction()
                : new StatementCompareResult(true, null, summary, verified);
    }
}
