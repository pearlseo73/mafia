# LLM 호출 설정 (GMS · Spring AI 2.0)

> 최종 수정: 2026-07-27
> 근거: 강사님 예제 `spring-ai-tool`, 이전 프로젝트 `report/`
> 환경·버전은 [개발환경.md](개발환경.md), 기능 설계는 [AI기능-구현설계.md](AI기능-구현설계.md)

---

## 1. GMS는 OpenAI 호환 프록시다

**업스트림 호스트를 경로에 끼워넣는 방식**이다.

```
https://gms.ssafy.io/gmsapi/api.openai.com/v1  +  /chat/completions
└──── 게이트웨이 ────┘└─ 업스트림 호스트 ─┘└v1┘     └ SDK가 붙임 ┘
```

```properties
gms.base-url=${GMS_BASE:https://gms.ssafy.io/gmsapi}
gms.key=${GMS_KEY:}

spring.ai.openai.base-url=${gms.base-url}/api.openai.com/v1
spring.ai.openai.api-key=${gms.key}
```

### ⚠️ `base-url`에 `/v1`이 필수다

Spring AI 2.0은 **공식 OpenAI Java SDK 기반**이라 SDK가 `/chat/completions`만 덧붙인다. 1.x는 자체 `RestClient`로 `/v1/chat/completions`를 붙였다. **`/v1`을 빼면 GMS가 404를 반환하고, 원인이 보이지 않아 오래 헤맨다.**

즉 `base-url + "/chat/completions"`가 올바른 최종 URL이 되는 지점까지 적는다.

---

## 2. 프로퍼티 경로가 버전 사이에 바뀌었다

**Spring AI 2.0에서 `options` 단계가 없어졌다.** 이전 프로젝트 설정을 그대로 복사하면 안 된다.

| | Spring AI 1.1.x | **Spring AI 2.0 (우리)** |
|---|---|---|
| 모델 | `spring.ai.openai.chat.options.model` | **`spring.ai.openai.chat.model`** |
| temperature | `...chat.options.temperature` | **`...chat.temperature`** |
| 토큰 상한 | `...chat.options.maxTokens` | **`...chat.max-tokens`** |
| reasoning 토큰 상한 | — | **`...chat.max-completion-tokens`** |

**Spring Boot는 모르는 프로퍼티를 에러로 만들지 않는다.** 1.x 경로를 2.0에 쓰면 조용히 무시되고 **기본 모델(`gpt-4o-mini`)로 호출된다.** 어떤 모델로 나갔는지 로그로 한 번 확인할 것.

### 프로퍼티 = 기본값, 코드 = 호출별 덮어쓰기

```java
.options(OpenAiChatOptions.builder()
        .model("gpt-4o-mini").temperature(0.8).build())
```

마피아는 기능마다 모델이 달라야 하므로 이 조합을 쓴다.

---

## 3. `gpt-5.4-mini` 실측 결과 (2026-07-27)

GMS로 직접 호출해 확인한 것이다. **일반적인 reasoning 모델 상식과 다른 부분이 있다.**

| 항목 | 결과 |
|---|---|
| 호출 | ✅ `gpt-5.4-mini` → `gpt-5.4-mini-2026-03-17`로 해석됨 |
| `max_tokens` | ❌ **HTTP 400** — `Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.` |
| `max_completion_tokens` | ✅ 정상 |
| `temperature=0.2` | ✅ **거부되지 않았다** (HTTP 200) |
| `reasoning_tokens` | **0** — 짧은 판정 요청에서는 추론 토큰을 쓰지 않았다 |
| 스트리밍 첫 본문 글자 | **1.13초** (69청크 / 전체 1.52초) |

### 따라서 정리하면

- **`max_tokens`를 쓰면 400이 난다.** `gpt-5.x` 계열은 반드시 `max-completion-tokens`.
- **`temperature`는 400을 내지 않는다.** 다만 200이 왔다는 것만으로 *반영된다*고 단정할 수 없다 — GMS나 모델이 조용히 무시할 수 있다. 창작 기능(칭호·신문)에서 다양성이 실제로 달라지는지는 같은 프롬프트를 `temperature` 0과 1로 여러 번 돌려 비교해야 확인된다.
- **추론 토큰이 한도를 잡아먹어 출력이 잘리는 문제는 관찰되지 않았다** (`reasoning_tokens=0`). 긴 추론이 필요한 프롬프트에서는 달라질 수 있으니 조간신문·칭호처럼 출력이 긴 기능에서는 여유를 둔다.
- **스트리밍 초반 침묵이 없다.** 첫 글자가 1.13초에 나왔고 `gpt-4o-mini`(1.03초)와 거의 같다. **AI 심판 연출이 reasoning 모델로도 성립한다.**

---

## 4. 기능별 모델·파라미터 방침

| 기능 | 모델 | 파라미터 | 근거 |
|---|---|---|---|
| 🔍 진술대조권 | `gpt-5.4-mini` | `max-completion-tokens` 넉넉히 | "의견 변경"과 "과거 진술 왜곡"을 구분하는 추론이 핵심 |
| 🏷️ 키워드 뽑기 | `gpt-4o-mini` | temperature 0, 토큰 낮게 | 단순 추출인데 **토론 중 호출**이다. 가장 싸고 빠른 쪽 |
| ⚖️ AI 심판 | **`gpt-5.4-mini`** | `max-completion-tokens` | 스트리밍 초반 침묵이 없음이 실측으로 확인됐으므로(§3) 판정 품질을 택한다 |
| 📰 조간신문 | `gpt-4o-mini` | temperature 0.7~0.8 | 창작. 500자면 1024토큰으로 충분 |
| 🏆 칭호 부여 | `gpt-4o-mini` | temperature 0.8~0.9 | 대사가 재미있어야 한다 |

`gpt-4o-mini`는 `max_tokens`를 쓰고 `gpt-5.4-mini`는 `max_completion_tokens`를 쓴다. **모델을 바꾸면 파라미터 이름도 같이 바꿔야 한다** — 안 그러면 400이다.

> `gpt-5.4-mini`로 함수 호출이 안 되면 `gpt-4o-mini`로 폴백 (강사님 주석).

**토큰 상한도 기능별로 다르게 준다.** 전역값 하나로는 조간신문과 키워드 뽑기를 동시에 만족시킬 수 없다.

### 응답 시간 실측 (GMS 경유, 2026-07-27)

| 호출 | 시간 |
|---|---|
| `gpt-4o-mini` 짧은 응답 | 1.30초 |
| `gpt-5.4-mini` 짧은 판정 | 1.51초 |
| `gpt-5.4-mini` 50토큰 응답 | 2.35초 |
| 스트리밍 첫 글자 | 1.0~1.1초 |

프록시를 거쳐도 **1~2.5초**다. 토론 중 호출되는 아이템 2종도 감당 가능한 수준이며, [T8](결정필요사항.md)(토론 잔여 시간 부족 시 차단 임계값)을 잡는 근거가 된다. 다만 실제 프롬프트는 발화 로그가 들어가 입력이 훨씬 길어지므로 재측정이 필요하다.

---

## 5. 키 관리

```properties
# .env 자동 로드 (없으면 무시 / OS 환경변수가 우선)
spring.config.import=optional:file:.env[.properties]

gms.key=${GMS_KEY:}          # ✅ 기본값을 비워둔다
```

- `.env`는 **반드시 `.gitignore`에** 넣는다
- `${VAR:실제키}` 형태로 **기본값 자리에 실제 키를 쓰지 않는다.** 그 자체가 커밋되는 유출이다 (강사님 예제의 OpenWeather 키가 이 경우)
- 팀 레포에서는 팀 방식을 확인한다 — `application-secret.yml` gitignore 또는 CI 환경변수. 배포 파이프라인이 `.env`를 읽지 않으면 로컬만 되고 서버에서 안 된다

---

## 6. 연결 검증 결과 (2026-07-27)

| 항목 | 결과 |
|---|---|
| 인증 (`Authorization: Bearer`) | ✅ GMS 발급 키(`S15P...` 접두어)로 통과 |
| 응답 형식 | ✅ **OpenAI 호환** — `choices[0].message.content`, `usage` 그대로 |
| **스트리밍(SSE)** | ✅ **진짜 스트리밍** — 61청크가 1.03초~1.82초에 걸쳐 도착. **AI 심판 연출 가능** |
| `GET /models` | ❌ **미지원** — `[GMS 에러] Model not found in request for domain api.openai.com` |

### `/models`를 프록시하지 않는다

GMS는 요청에 모델이 지정돼야 하는 구조라 모델 목록 조회가 안 된다. **사용 가능한 모델명을 API로 발견할 수 없으므로**, SSAFY가 제공한 문서를 보거나 호출해서 400이 나는지로 확인해야 한다.

확인된 모델: `gpt-4o-mini`(→ `gpt-4o-mini-2024-07-18`), `gpt-5.4-mini`(→ `gpt-5.4-mini-2026-03-17`)

### 재검증용 스크립트

일회성 확인이라 리포에 넣지 않았다. 다시 필요하면 `.env`를 읽어 세 가지(모델 목록 · 일반 호출 · 스트리밍)를 찍는 스크립트를 임시로 만들면 된다. `curl`로도 된다.

```bash
source .env
curl -N $GMS_BASE/api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $GMS_KEY" -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"핑"}],"stream":true}'
```

### 남은 확인 항목

- **`temperature`가 실제로 반영되는지** (§3) — 거부되지는 않으나 무시될 가능성
- **`response_format`(json_schema) 지원 여부** — 지원하면 프롬프트 대신 API 레벨로 출력 형식 강제 가능
- **실제 프롬프트 길이에서의 응답 시간** — 발화 로그가 들어가면 입력이 훨씬 길어진다

---

## 7. 구조화 출력 — `BeanOutputConverter`가 프롬프트를 바꾼다

`.entity(Dto.class)`를 호출하면 Spring AI가 **DTO에서 JSON Schema를 만들어 프롬프트 뒤에 형식 지시문을 덧붙인다.** 모델이 받는 프롬프트는 내가 쓴 것 + 그 블록이다.

따라오는 결과 세 가지.

1. **형식 지시를 프롬프트에 중복해서 쓰지 않는다.** "JSON으로만 답하라" 류는 Spring AI가 알아서 붙인다
2. **DTO 필드명이 프롬프트의 일부다.** `@JsonPropertyDescription`을 붙이면 설명이 스키마에 들어가므로, DTO에 주석 쓰듯 적으면 프롬프트가 된다
   ```java
   @JsonPropertyDescription("서로 충돌하는 발화 번호 2개 이상")
   private List<String> utteranceIds;
   ```
3. **실제로 보낸 프롬프트를 한 번은 눈으로 본다.** `SimpleLoggerAdvisor`를 붙이면 최종 프롬프트가 로그로 나온다

> GMS가 `response_format`(json_schema)을 지원하면 프롬프트로 부탁하는 대신 **API 레벨에서 형식을 강제**할 수 있어 더 안정적이다. 6절 확인 항목에 포함.

---

## 8. 참고 코드 — `report/`

이전 프로젝트(가계부 앱)의 Spring AI 구현이다. **참고용이며 이 프로젝트의 코드가 아니다.** Jackson 2를 쓰므로 Boot 4에서 그대로 컴파일되지 않는다.

**재사용할 패턴**

```java
// 구조화 출력 — 진술대조 · 키워드 · 칭호
Dto d = chatClient.prompt().system(system).user(user).call().entity(Dto.class);

// 평문 — 조간신문
String s = chatClient.prompt().system(system).user(user).call().content();
```

- try/catch로 전체를 감싸 실패를 폴백으로 흡수 → [결정필요사항 T11](결정필요사항.md)(AI 심판 폴백)에 그대로 대응
- **사실관계는 코드가 계산하고 AI는 문장만 쓴다** (`computeMood` → 프롬프트로 주입). [§5-2](AI기능-구현설계.md)의 칭호 하이브리드 설계와 같은 원칙

**없는 것** — 스트리밍(AI 심판에 필수), 타임아웃 설정.
