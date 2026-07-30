# LLM 호출 설정 (GMS · Spring AI 2.0)

> 최종 수정: 2026-07-30 (§4 조간신문 파라미터·응답시간)
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
        .model("gpt-4o-mini").maxTokens(512))     // ← .build() 를 붙이지 않는다
```

마피아는 기능마다 모델이 달라야 하므로 이 조합을 쓴다.

**Spring AI 2.0의 `.options()`는 `Builder`를 받는다.** 1.x는 완성된 `ChatOptions`를 받았으므로 `.build()`를 붙이는 예제가 많은데, 2.0에서 그대로 쓰면 컴파일되지 않는다.

```
method options in interface ChatClientRequestSpec cannot be applied to given types;
  required: B    found: OpenAiChatOptions
```

### ⚠️ 토큰 상한을 전역 프로퍼티에 두면 안 된다

`spring.ai.openai.chat.max-tokens`를 두면 **`gpt-5.x` 계열 호출이 전부 깨진다.** 호출에서 `.options()`로 `model`만 덮어써도 기본 옵션의 `max_tokens`가 병합돼 함께 나가고, `gpt-5.x`는 그것을 받으면 HTTP 400이다(§3). 런타임 옵션은 **필드 단위로 덮어쓰기**라서 값을 비워 지울 수 없다.

기능별로 원하는 출력 길이도 다르다(조간신문 500자 vs 키워드 10자×5). **모델과 토큰 상한은 항상 호출 쪽 `.options()`에서 짝으로 준다.**

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

### ⚠️ `temperature=0`이어도 출력이 매번 다르다 (2026-07-28 실측)

진술대조권 프롬프트로 확인했다. **코드·프롬프트·입력을 하나도 바꾸지 않고 연속 두 번 호출했는데 결과가 달랐다.**

| | 1회차 | 2회차 |
|---|---|---|
| 모순 묶음 수 | 4개 | 2개 |
| 묶음 구성 | `[u2,u6]` `[u2,u7]` `[u6,u8]` `[u6,u9]` | `[u2,u6,u7,u9]` `[u4,u8]` |

`spring.ai.openai.chat.temperature=0`이 설정된 상태였다. **§6의 "`temperature`가 반영되는지" 확인 항목에 대한 부분적인 답이다** — 최소한 `gpt-5.4-mini`에서 `temperature=0`이 재현성을 주지는 않는다. GMS가 무시하는지, 모델이 원래 그런지는 아직 구분되지 않았다.

프롬프트 검증에 그대로 영향이 있다. **한 번 돌려서 통과한 것을 통과라고 볼 수 없다.** 판정 품질을 볼 때는 같은 케이스를 2회 이상 돌리고, 매번 나오는 것과 흔들리는 것을 구분해야 한다.

---

## 4. 기능별 모델·파라미터 방침

| 기능 | 모델 | 파라미터 | 근거 |
|---|---|---|---|
| 🔍 진술대조권 | `gpt-5.4-mini` | `max-completion-tokens` 넉넉히 | "의견 변경"과 "과거 진술 왜곡"을 구분하는 추론이 핵심 |
| 🏷️ 키워드 뽑기 | `gpt-4o-mini` | temperature 0, 토큰 낮게 | 단순 추출인데 **토론 중 호출**이다. 가장 싸고 빠른 쪽 |
| ⚖️ AI 심판 | **`gpt-5.4-mini`** | `max-completion-tokens` | 스트리밍 초반 침묵이 없음이 실측으로 확인됐으므로(§3) 판정 품질을 택한다 |
| 📰 조간신문 | `gpt-4o-mini` | **temperature 0.8 · `max_tokens` 300** | 창작. **300이 길이 상한 노릇을 한다** — 1토큰 ≈ 1.5자라 450자쯤에서 막힌다. 프롬프트에 "500자 이내"를 적어도 안 지켜졌다 (07-30 실측) |
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
| **`gpt-5.4-mini` 진술대조 (입력 발화 9건)** | **1.9~3.3초** |
| **`gpt-5.4-mini` 진술대조 (입력 발화 15건)** | **3.8초** |
| `gpt-4o-mini` 키워드 뽑기 (입력 발화 4건) | 1.5~2.5초 |
| 📰 조간신문 (입력 28건 780자 → 출력 330자) | **미측정** |

**조간신문은 아직 안 재봤다.** 위 값들은 출력이 짧은 판정·추출이라 500자 창작에 그대로 쓸 수 없다. **생성 창이 R1 4초 / R2 9초뿐이라 이 값이 재시도 여부를 정한다** → [조간신문 §4](../log_analysis/prompts/조간신문.md). `write_newspaper()`가 `sec`를 돌려주므로 R1·R2·R3 3회 실행에서 함께 받는다.

아래 두 줄이 실제 프롬프트로 측정한 값이다(2026-07-28). **짧은 판정(1.5초)보다 느리다** — 입력이 길어지고 출력이 모순 묶음 여러 개로 늘어난 만큼이다. 발화가 15건이면 4초에 가까워지므로, 라운드가 쌓인 뒤 R3에서 쓰면 더 느려진다. 토론 중 호출이라 이 지연이 유저에게 그대로 보인다 → [T8](결정필요사항.md).

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

## 8. `ChatClient` 사용 패턴

이전 프로젝트(가계부 앱)와 강사님 예제에서 가져온 것이다. 원본 코드는 `reference/`에 로컬로만 두고 **git에 커밋하지 않으므로**(남의 저작물) 필요한 조각을 여기에 옮겨 적는다.

### 빈 등록 — `ChatClient.Builder` 가 아니라 `ChatClient` 를 주입받는다

```java
@Configuration
public class AiConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```

스타터가 자동으로 등록해주는 것은 `Builder`다. 그것을 **한 번만** `build()` 해서 `ChatClient` 빈으로 등록하고, 쓰는 쪽에서는 `ChatClient`를 받는다. 호출마다 `build()` 하지 않는다. 강사님 예제가 이 방식이다.

기능별로 모델·파라미터가 달라야 하는 경우도 빈을 여러 개 만들지 않고 호출 시 `.options(OpenAiChatOptions.builder()...build())`로 덮어쓴다 (§4).

### 구조화 출력 — 진술대조 · 키워드 · 칭호

```java
KeywordResult r = chatClient.prompt()
        .system(s -> s.text(SYSTEM_PROMPT, StandardCharsets.UTF_8))
        .user(u -> u.text(USER_PROMPT, StandardCharsets.UTF_8)
                .param("targetName", target)
                .param("round", round)
                .param("utterances", lines))
        .call()
        .entity(KeywordResult.class);
```

- **`text(resource, StandardCharsets.UTF_8)` — 인자 2개짜리를 쓴다.** 1개짜리는 플랫폼 기본 인코딩을 타서 한글 프롬프트가 깨질 수 있다
- 리소스는 `new ClassPathResource("prompts/...")`로 `static final` 상수로 두면 `@Value` 필드 주입과 `@RequiredArgsConstructor`를 섞지 않아도 된다

### 프롬프트는 `.st` 파일로 뺀다 — 강사님 예제와 의도적으로 다른 부분

강사님 예제는 시스템 프롬프트를 클래스 안에 텍스트 블록으로 둔다.

```java
private static final String SYS_RULES = """
        너는 Tool(도구 호출) 기반 에이전트다.
        ...
        """;
```

**우리는 `src/main/resources/prompts/*.st`로 뺀다.** 이유는 수정 빈도다.

| | 강사님 (텍스트 블록) | 우리 (`.st` 파일) |
|---|---|---|
| 프롬프트 수정 | **재컴파일 필요** | 파일만 고치면 됨 |
| 길이 | 10~15줄 | 규칙 5개 + 발화 로그 |
| 수정 빈도 | 실습용, 거의 안 고침 | **하루에 수십 번 반복** |

이 프로젝트에서는 프롬프트 자체가 산출물이고 반복 튜닝이 작업의 본체다. 재컴파일 한 번이 루프에 끼면 그 비용이 계속 누적된다.

**주의:** `.st`는 StringTemplate 파일이고 `{}`가 변수 구분자다. **JSON 예시를 `.st`에 넣으면 깨진다.** 출력 형식 지시는 `.st`에 쓰지 않고 `BeanOutputConverter`에 맡긴다 (§7).

파일명은 `{기능}-{system|user}.st`로 통일한다.

### 평문 출력 — 조간신문

```java
String article = chatClient.prompt().system(system).user(user).call().content();
```

### 실패 폴백

LLM 호출 전체를 try/catch로 감싸 예외를 폴백으로 흡수한다. 키 오류·네트워크·파싱 실패를 한 곳에서 처리하고 앱이 죽지 않게 한다 → [결정필요사항 T11](결정필요사항.md)(AI 심판 폴백)에 그대로 대응.

### 사실관계는 코드가 계산하고 AI는 문장만 쓴다

가계부 앱에서 `mood`(표정 4종)를 예산 초과 주 수로 **코드가 결정**하고, AI에게는 "이번 달 기분은 `smirk`야"로 주입해 그 톤으로만 글을 쓰게 했다. AI가 `mood`를 고르게 하면 지표와 어긋난다.

[§5-2](AI기능-구현설계.md)의 칭호 하이브리드 설계가 같은 구조다 — 팀킬 횟수는 서버가 세고 LLM은 칭호명·대사만 만든다.

### 아직 안 써본 것

- **스트리밍** (`.stream()`) — AI 심판에 필수. GMS 지원은 §6에서 확인됨
- **타임아웃 설정** — 아이템 2종은 토론 중 호출이라 지연이 그대로 노출된다
