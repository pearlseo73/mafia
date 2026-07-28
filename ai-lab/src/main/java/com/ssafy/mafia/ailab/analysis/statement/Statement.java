package com.ssafy.mafia.ailab.analysis.statement;

/**
 * 진술대조권의 <b>입력 1건</b>. 지금은 파일 픽스처가, 나중에는 Redis
 * ({@code utt:{roomId}:public})가 이 형태로 넘긴다.
 *
 * <p>화자 이름이 없다. 진술대조권은 <b>대상 1명의 발화만</b> 받기 때문이다. 전체 대화를
 * 주면 LLM 이 다른 사람의 발언을 대상의 발언으로 인용하는 실수가 생기는데, 애초에 대상의
 * 발화만 넣으면 그 오류가 구조적으로 불가능해진다 → log_analysis/prompts/진술대조.md §0
 *
 * @param round 1, 2, 3 — <b>프롬프트에 반드시 들어간다.</b> 라운드 정보가 없으면 "처음부터
 *              믿었다"가 과거에 대한 주장임을 LLM 이 알 수 없어 모순을 놓친다
 * @param time  "21:01:12" — 프롬프트에는 넣지 않는다. 토큰만 쓰고 판정에 쓸모가 없다.
 *              번호를 원문으로 되돌릴 때 화면 표시용으로만 쓴다
 * @param text  발화 원문. LLM 은 이것을 <b>다시 쓰지 않는다</b>
 */
public record Statement(int round, String time, String text) {
}
