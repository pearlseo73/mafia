package com.ssafy.mafia.ailab.analysis.keyword;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 키워드 뽑기 (`KEYWORD_EXTRACT`) — 지목한 플레이어가 <b>이번 라운드에</b> 한 말에서
 * 특징적인 표현을 뽑아낸다.
 *
 * <p>입력은 호출자가 만들어 넘긴다. 지금은 파일 픽스처가, 나중에는 Redis
 * ({@code utt:{roomId}:public})가 넘긴다. 이 서비스는 어디서 왔는지 모른다.
 *
 * <p><b>환각 방어를 서버가 한다.</b> LLM 이 로그에 없는 표현을 만들어내도
 * {@link #dropFabricated}에서 걸러진다. 프롬프트 품질과 무관하게 보장된다
 * → docs/AI기능-구현설계.md §7-5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordExtractService {

    /** 이 미달이면 LLM 을 호출하지 않는다. 비용·지연·환각을 동시에 줄인다. */
    private static final int MIN_UTTERANCES = 3;
    private static final int MIN_CHARS = 40;

    private static final Resource SYSTEM_PROMPT =
            new ClassPathResource("prompts/keyword-extract-system.st");
    private static final Resource USER_PROMPT =
            new ClassPathResource("prompts/keyword-extract-user.st");

    private final ChatClient chatClient;

    /**
     * @param targetName 지목 대상
     * @param round      현재 라운드 (진술대조권과 달리 이 라운드 발화만 본다 — §7-1)
     * @param utterances 대상이 이번 라운드에 한 발화. 아이템 사용 시각 이전만
     */
    public KeywordResult extract(String targetName, int round, List<String> utterances) {
        if (!hasEnoughToAnalyze(utterances)) {
            log.debug("발화 부족 → LLM 호출 생략. target={} round={} 건수={}",
                    targetName, round, utterances.size());
            return KeywordResult.insufficient();
        }

        String joined = String.join("\n", utterances);
        try {
            KeywordResult raw = chatClient.prompt()
                    .system(s -> s.text(SYSTEM_PROMPT, StandardCharsets.UTF_8))
                    .user(u -> u.text(USER_PROMPT, StandardCharsets.UTF_8)
                            .param("targetName", targetName)
                            .param("round", round)
                            .param("utterances", joined))
                    .call()
                    .entity(KeywordResult.class);

            return raw == null ? KeywordResult.unavailable() : dropFabricated(raw, joined);
        } catch (Exception e) {
            // 키 오류·네트워크·파싱 실패를 한 곳에서 흡수한다. 게임이 멈추면 안 된다.
            log.warn("[keyword] 추출 실패 → 폴백. target={} : {}", targetName, e.toString(), e);
            return KeywordResult.unavailable();
        }
    }

    /**
     * 발화가 분석할 만큼 있는지. 미달이면 "분석할 발언이 부족합니다"로 즉시 응답한다.
     * 현재 라운드만 보기 때문에 진술대조권보다 훨씬 자주 걸린다.
     */
    public static boolean hasEnoughToAnalyze(List<String> utterances) {
        if (utterances == null || utterances.size() < MIN_UTTERANCES) {
            return false;
        }
        return utterances.stream().mapToInt(String::length).sum() >= MIN_CHARS;
    }

    /**
     * 원문에 없는 키워드를 버린다 — 키워드 뽑기의 환각 방어.
     *
     * <p>진술대조권은 LLM 이 발화 번호만 반환하게 해서 인용 환각을 막지만, 키워드는
     * 원문의 일부라 성질이 다르다. 그래서 <b>부분 문자열 검사</b>로 막는다.
     *
     * <p>공백을 제거하고 비교하므로 "시간낭비"도 "시간 낭비"에 매칭된다. 조사·어미를 뗀
     * "억울"은 "억울하면"의 부분 문자열이라 통과한다. 반대로 로그에 없는 상위 개념어
     * ("비협조", "방어적")는 통과하지 못한다 — 그것은 추출이 아니라 LLM 의 해석이다.
     */
    private KeywordResult dropFabricated(KeywordResult raw, String source) {
        String haystack = source.replaceAll("\\s", "");
        List<String> kept = raw.keywordsOrEmpty().stream()
                .filter(k -> haystack.contains(k.replaceAll("\\s", "")))
                .toList();

        int dropped = raw.keywordsOrEmpty().size() - kept.size();
        if (dropped > 0) {
            log.warn("[keyword] 원문에 없는 키워드 {}개 제거: {}", dropped,
                    raw.keywordsOrEmpty().stream()
                            .filter(k -> !haystack.contains(k.replaceAll("\\s", "")))
                            .toList());
        }
        return raw.withKeywords(kept);
    }
}
