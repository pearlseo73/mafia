package com.ssafy.mafia.ailab.fixture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그 픽스처 로더. Redis 가 붙기 전까지 {@code getPublicUtterances(roomId)} 자리를 대신한다.
 *
 * <p><b>공개 로그만 읽는다.</b> 밤 마피아 로그를 읽는 메서드는 이 클래스에 존재하지 않는다.
 * 조간신문·AI 심판·진술대조권·키워드 뽑기가 밤 로그에 닿을 경로를 코드 수준에서 없애기 위한 것이다
 * (AI기능-구현설계 §2-2 의 "가져오는 키가 애초에 다르다"와 같은 접근).
 */
public final class UtteranceLog {

    /** ai-lab 이 mafia-ai/ai-lab 이므로 픽스처는 상위 폴더에 있다. Gradle 테스트의 작업 디렉터리는 프로젝트 폴더다. */
    private static final Path PUBLIC_LOG =
            Path.of("..", "log_analysis", "scenario", "scenario1", "발화로그-공개.txt");

    /** [21:00:15] R1/DAY_DISCUSSION 강예린: 저는 그런 거 시간 낭비라고 ... */
    private static final Pattern LINE =
            Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})]\\s+R(\\d+)/(\\S+)\\s+([^:]+):\\s*(.*)$");

    /**
     * 밤 로그 유출 검사용 문구. 밤 로그 <b>파일을 읽지 않고</b> 문구만 박아둔다 —
     * 파일을 읽는 코드가 생기면 언젠가 그 경로가 프롬프트로 이어진다.
     */
    private static final List<String> NIGHT_LOG_PHRASES = List.of(
            "그래야 안 걸려요", "최지우로 하죠", "오늘 누구 죽일까요", "제가 지정할게요",
            "신문도 손볼게요", "혼자 남았네", "이하윤으로 하자", "눈치챈 것 같은데");

    private UtteranceLog() {
    }

    /** 공개 로그 전체 (낮 계열 페이즈만 담긴 파일이다). */
    public static List<Utterance> loadPublic() {
        try {
            return Files.readAllLines(PUBLIC_LOG, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(LINE::matcher)
                    .filter(Matcher::matches)
                    .map(m -> new Utterance(
                            m.group(1),
                            Integer.parseInt(m.group(2)),
                            m.group(3),
                            m.group(4).trim(),
                            m.group(5).trim()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "픽스처를 읽지 못했다: " + PUBLIC_LOG.toAbsolutePath(), e);
        }
    }

    /**
     * 키워드 뽑기용 입력 — 대상 1명 · <b>현재 라운드만</b> · 아이템 사용 시각 이전.
     *
     * <p>라운드를 현재 것으로 제한하는 게 진술대조권과의 차별점이다 (§7-1).
     * 사용 시각 이후 발화는 그 시점에 존재하지 않았으므로 넣지 않는다.
     */
    public static List<Utterance> forKeywordExtract(String speaker, int round, String usedAt) {
        return loadPublic().stream()
                .filter(u -> u.speaker().equals(speaker))
                .filter(u -> u.round() == round)
                .filter(u -> u.time().compareTo(usedAt) < 0)
                .toList();
    }

    /**
     * 진술대조권용 입력 — 대상 1명 · <b>전체 라운드</b> · 사용 시각 이전.
     * 라운드 간 말이 바뀌는 것을 봐야 하므로 라운드를 제한하지 않는다.
     */
    public static List<Utterance> forStatementCompare(String speaker, String usedAt) {
        return loadPublic().stream()
                .filter(u -> u.speaker().equals(speaker))
                .filter(u -> u.time().compareTo(usedAt) < 0)
                .toList();
    }

    /**
     * 서비스에 넘길 형태 — 발화 본문만. 나중에 Redis 가 이 자리를 대신한다.
     * 임계값 판정·환각 필터는 서버 로직이므로 {@code src/main} 의 서비스에 있다.
     */
    public static List<String> texts(List<Utterance> utterances) {
        return utterances.stream().map(Utterance::text).toList();
    }

    /**
     * 낮 기능 출력에 밤 로그 문구가 섞였는지. 하나라도 걸리면 마피아 신원이 노출된 것이다.
     * 눈으로는 놓치므로 이것만은 자동 검사로 둔다.
     */
    public static void assertNoNightLogLeak(String output) {
        for (String phrase : NIGHT_LOG_PHRASES) {
            if (output.contains(phrase)) {
                throw new AssertionError("밤 로그 유출: \"" + phrase + "\" 가 출력에 있다 → " + output);
            }
        }
    }
}
