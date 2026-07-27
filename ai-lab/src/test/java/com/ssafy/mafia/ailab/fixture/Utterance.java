package com.ssafy.mafia.ailab.fixture;

/**
 * 발화 1건. Redis 의 {@code utt:{roomId}:public} 원소에 대응한다.
 *
 * @param time    "21:00:15" — 같은 날 안이라 문자열 비교로 시각 순서를 판단할 수 있다
 * @param round   1, 2, 3
 * @param phase   DAY_START | DAY_DISCUSSION | DAY_VOTE | FINAL_DEFENSE | AI_JUDGMENT | RESULT
 * @param speaker 화자 이름 (실제로는 playerId)
 * @param text    발화 원문. STT 를 모사해 구두점이 없다
 */
public record Utterance(String time, int round, String phase, String speaker, String text) {

    @Override
    public String toString() {
        return "[%s] R%d/%s %s: %s".formatted(time, round, phase, speaker, text);
    }
}
