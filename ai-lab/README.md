# ai-lab

프롬프트를 반복 검증하기 위한 **실험실**이다. 실서비스 구현은 팀 백엔드 레포로 옮긴다.
전체 맥락은 리포 루트의 [CLAUDE.md](../CLAUDE.md) · [docs/개발환경.md](../docs/개발환경.md) · [docs/LLM호출설정.md](../docs/LLM호출설정.md).

---

## 폴더 구분 기준 — `src/main`은 옮길 것, `src/test`는 남을 것

```
src/main/                                    ← 팀 레포로 그대로 이식
├─ java/com/ssafy/mafia/ailab/
│  ├─ AiLabApplication.java                    (스캐폴딩, 이식 안 함)
│  ├─ config/AiConfig.java                     ChatClient 빈 등록
│  └─ analysis/                                기능별 (도메인별 패키지)
│     └─ keyword/
│        ├─ KeywordExtractService.java           LLM 호출 · 임계값 · 환각 필터
│        └─ KeywordResult.java                   LLM 출력 계약 (record)
└─ resources/
   ├─ application.properties                   GMS 설정
   └─ prompts/*.st                             프롬프트 (정본)

src/test/                                    ← ai-lab 에만 남는다
└─ java/com/ssafy/mafia/ailab/
   ├─ fixture/   로그 픽스처 로더 — Redis 가 대체하면 버린다
   ├─ prompt/    프롬프트 검증 — 결과를 콘솔에 찍어 눈으로 본다
   └─ smoke/     연결 확인 — 일회성
```

**픽스처 로더를 `test`에 두는 게 중요하다.** `main`에 두면 실서비스 코드가 파일에서 발화 로그를 읽는 경로가 생긴다. 실제로는 Redis(`utt:{roomId}:public`)에서 와야 한다.

반대로 **임계값 판정과 환각 필터는 `main`의 서비스에 있다.** 그것은 테스트 편의가 아니라 서버가 실제로 해야 하는 일이다. 테스트는 입력을 만들어 넘기고 결과를 찍기만 한다.

코드 스타일(Lombok, 패키지 구조, `.st` 사용 이유)은 [docs/개발환경.md §4-1](../docs/개발환경.md)에 정리돼 있다.

## 실행

```bash
./gradlew test                                        # 전체
./gradlew test --tests "*GmsSmokeTest*"               # GMS 연결만
./gradlew test --tests "*KeywordExtractPromptTest*"   # 키워드 뽑기 프롬프트
```

`build.gradle`에 `showStandardStreams = true`를 켜둬서 **프롬프트 결과가 콘솔에 그대로 찍힌다.** 기대답안과 눈으로 비교하는 것이 이 프로젝트의 사용법이다.

터미널에서 돌릴 때 `JAVA_HOME`이 없으면 이렇게 지정한다 (IntelliJ 안에서는 불필요).

```bash
export JAVA_HOME="C:/Users/SSAFY/.jdks/ms-25.0.4"
```

## 의존성을 늘리지 않는다

**Spring AI OpenAI 스타터 하나뿐이다.** Web·JPA·MySQL·Redis 를 넣으면 프롬프트 한 줄 고칠 때마다 인프라를 띄워야 하고, `@SpringBootTest`가 DB 에 붙으려다 실패한다.

## 자동 검사는 두 개만

기대답안은 문자열 일치 검증용이 아니다 — **형식과 판정은 눈으로 본다.** 눈으로 놓치는 것만 코드로 고정했다.

| 검사 | 어디 |
|---|---|
| 키워드가 입력 발화의 부분 문자열인지 (환각 방어) | `KeywordExtractPromptTest#assertVerbatim` |
| 밤 로그 문구가 출력에 섞였는지 (마피아 신원 유출) | `UtteranceLog#assertNoNightLogLeak` |

`UtteranceLog`에는 **밤 로그를 읽는 메서드가 없다.** 유출 검사 문구도 파일을 읽지 않고 상수로 박아뒀다 — 파일을 읽는 코드가 생기면 언젠가 그 경로가 프롬프트로 이어진다.
