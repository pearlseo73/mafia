package com.ssafy.mafia.ailab.analysis.keyword;

import java.util.List;

/**
 * 키워드 뽑기의 <b>LLM 출력 계약</b>. Spring AI 가 이 타입으로 JSON Schema 를 만들어
 * 모델에 형식을 강제하고, {@code .call().entity(KeywordResult.class)} 로 역직렬화한다.
 *
 * <p>{@code requestId}·{@code itemCode}·{@code targetPlayerId} 는 <b>서버가 아는 값</b>이라
 * 여기에 없다. LLM 에게 만들라고 하면 환각 지점만 늘어난다. 서버가 이 결과를 감싸서
 * {@code item.analysis.result} 로 내보낸다 (AI-API명세 §2-5).
 *
 * <p>LLM 출력 계약은 값 객체라 {@code record} 로 둔다. Lombok DTO 와 달리 setter 가 없어
 * 서버가 받은 뒤 임의로 바꿀 수 없다.
 *
 * @param hasFindings     뽑을 키워드가 있었는지. false 는 에러가 아니라 정상 응답이다
 * @param noFindingReason hasFindings=false 일 때만. 화면 문구는 서버가 이 값으로 고른다
 * @param keywords        3~5개. 입력 발화에 실제로 등장하는 표현이어야 한다
 */
public record KeywordResult(
        boolean hasFindings,
        String noFindingReason,
        List<String> keywords) {

    /** 발화가 임계값 미달 — LLM 을 호출하지 않고 서버가 만든다 (AI기능-구현설계 §7-5). */
    public static KeywordResult insufficient() {
        return new KeywordResult(false, "INSUFFICIENT", List.of());
    }

    /** LLM 호출 실패 — AI-API명세 §3 의 {@code AI_UNAVAILABLE} 에 대응한다. */
    public static KeywordResult unavailable() {
        return new KeywordResult(false, "AI_UNAVAILABLE", List.of());
    }

    public List<String> keywordsOrEmpty() {
        return keywords == null ? List.of() : keywords;
    }

    /** 서버가 검증·필터링한 뒤 키워드만 갈아끼운다. */
    public KeywordResult withKeywords(List<String> filtered) {
        return filtered.isEmpty()
                ? unavailable()
                : new KeywordResult(true, null, filtered);
    }
}
