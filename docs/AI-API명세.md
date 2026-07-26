# AI 파트 API 명세 (초안)

> 최종 수정: 2026-07-26
> 관련 문서: [AI기능-구현설계.md](AI기능-구현설계.md) · [기술스택-검토.md](기술스택-검토.md) · [결정필요사항.md](결정필요사항.md)

팀 API 명세서에 넣을 **AI 파트 관련 엔드포인트·이벤트**만 정리했다.
경로·이벤트 네이밍은 팀 컨벤션에 맞춰 조정하고, **payload 계약과 수신 범위**를 그대로 가져가면 된다.

---

## 0. 공통 규칙

### 0-1. 클라이언트를 신뢰하지 않는 필드

아래는 **클라이언트가 보내지 않는다. 서버가 세션·게임 상태에서 유도한다.**

| 필드 | 이유 |
|---|---|
| `playerId` | WS 세션에서 유도. 보내게 하면 타인 명의 발화 위조 가능 |
| `roomId` | 세션에서 유도 (경로 파라미터로 받는 REST는 세션과 일치 검증) |
| `phase` / `round` | **가장 중요.** 클라이언트가 phase를 지정하면 밤 발화를 낮 발화로 위장해 공개 로그에 심을 수 있다 |
| 역할(마피아/시민) | 서버만 안다 |

### 0-2. REST와 WebSocket 선택 기준

| 성격 | 방식 |
|---|---|
| 빈번한 단방향 전송 (발화, 얼굴 점수) | **WS** — HTTP 핸드셰이크 오버헤드 회피, 순서 보장 |
| 서버가 밀어주는 결과 (신문, 심판, 칭호, 아이템 결과) | **WS** |
| 상태 변경 + 즉시 성공/실패 판정 필요 (아이템 사용, 신문 Apply) | **REST** |
| 재접속·새로고침 복구 조회 | **REST** |

---

## 1. REST API

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/api/v1/rooms/{roomId}/items/{itemCode}/use` | AI 아이템 사용 (진술대조권 / 키워드 뽑기) |
| `PUT` | `/api/v1/rooms/{roomId}/newspaper` | 조간신문 조작권 — Apply |
| `GET` | `/api/v1/rooms/{roomId}/result` | 결산 결과 (승패·역할·칭호) 재조회 |
| `GET` | `/api/v1/rooms/{roomId}/snapshot` | 재접속 복구 — AI 산출물 포함 (§4) |

### 1-1. AI 아이템 사용

```http
POST /api/v1/rooms/{roomId}/items/{itemCode}/use
Content-Type: application/json

{ "targetPlayerId": "p3" }
```

`itemCode`: `STATEMENT_COMPARE`(진술대조권) | `KEYWORD_EXTRACT`(키워드 뽑기)

**즉시 응답 — 수락 여부만.** 분석 결과는 §2-4의 WS로 나중에 도착한다.

```http
202 Accepted
{ "requestId": "req_a1b2", "status": "PENDING", "estimatedMs": 4000 }
```

`estimatedMs`는 프론트 로딩 UI 표시용이다. 이 "**즉시 ack + 비동기 결과**" 패턴을 명세서에 명시해야 프론트가 로딩 상태를 설계할 수 있다.

**서버 검증 순서** (실패 시 4xx, 아이템은 "사용 가능"으로 롤백)

1. 요청자 생존 여부
2. **역할 일치** — 진술대조권은 시민 전용. 클라이언트 요청을 신뢰하지 않는다
3. 현재 phase가 토론인지
4. 토론 잔여 시간 ≥ 임계값 ([결정필요사항 T8](결정필요사항.md))
5. 대상 플레이어 생존 여부 (자기 자신 지목은 허용 — 명세 16-9)
6. 요청자가 다른 액티브 아이템을 사용 중인지 (명세 16-7)
7. 아이템 보유·상태 (명세 16-8: 동일 상태 변경 요청은 무시)

### 1-2. 조간신문 Apply

```http
PUT /api/v1/rooms/{roomId}/newspaper
{ "content": "수정된 기사 본문..." }
```

- 마피아 + 밤 phase + 조작권 보유 검증
- 1000자 초과 시 `400` (명세 17-2)
- 성공 시 아이템을 **사용 완료**로 전이 (명세 17-5) → LLM 재호출 없음
- 편집 중 상태는 서버에 저장하지 않는다 (조작권은 "사용 중" 상태가 없음)

```http
200 OK
{ "round": 2, "content": "...", "appliedAt": 1721980000000 }
```

### 1-3. 결산 결과 조회

```http
GET /api/v1/rooms/{roomId}/result
```

```json
{
  "winner": "MAFIA",
  "players": [
    { "playerId": "p1", "nickname": "철수", "role": "MAFIA", "survived": true }
  ],
  "titles": [
    { "playerId": "p2", "title": "최고의 팀킬상",
      "quote": "오 당신의 날카로운 칼날은 항상 당신의 동료만을 향하는군요" }
  ],
  "titlesStatus": "READY"
}
```

`titlesStatus`: `PENDING` | `READY` | `FAILED`
칭호는 LLM 호출이라 2~5초 걸린다. 결산 화면을 먼저 띄우고 칭호만 나중에 채우는 구조(§2-5)이므로, 이 API도 `PENDING` 상태를 반환할 수 있어야 한다.

---

## 2. WebSocket 이벤트

### 2-1. 클라이언트 → 서버

| 이벤트 | 페이로드 | 비고 |
|---|---|---|
| `speech.utterance` | `{ text, startedAt, endedAt }` | STT 발화 1건 |
| `face.agitation` | `{ score }` | 최후변론 동요 지수 |

#### `speech.utterance`

```json
{ "text": "저는 어제 밤에 아무것도 안 했어요", "startedAt": 1721980000123, "endedAt": 1721980002456 }
```

- Web Speech API의 **final 결과만** 전송 (interim 제외)
- `text` 최대 500자 — 초과분은 서버가 절단
- 서버가 `playerId`·`phase`·`round`를 붙여 저장하고 **가시성을 결정**한다
  - 낮 계열 phase → `utt:{roomId}:public`
  - 밤 + 발화자가 마피아 → `utt:{roomId}:mafia:{round}`
  - 밤 + 발화자가 시민 → **버림** (명세 21-2에 따라 애초에 전송되지 않아야 하는 발화)
- 응답 없음 (fire-and-forget)
- 예상 유량: 6명 × 3분 토론 → 라운드당 수십~수백 건

#### `face.agitation`

```json
{ "score": 62 }
```

- 클라이언트는 10fps로 추론하되 **전송은 초 2~4회로 throttle**
- 서버 검증: 발신자가 최후변론 대상자인지 / phase가 최후변론인지 / `0~100` 클램프 / rate limit
- 검증 실패 시 조용히 폐기 (에러 응답 불필요)

### 2-2. 서버 → 클라이언트 — 수신 범위가 계약의 핵심

| 이벤트 | 수신 대상 | 시점 |
|---|---|---|
| `newspaper.preview` | **마피아만** | 밤 시작 (명세 17-3) |
| `newspaper.publish` | 전원 | 낮 시작 (명세 17-6) |
| `judgment.started` | 전원 | AI 심판 시작 |
| `judgment.reason` | 전원 | 스트리밍 청크 |
| `judgment.verdict` | 전원 | 대상 확정 |
| `judgment.failed` | 전원 | LLM 실패 폴백 |
| `face.agitation` | 전원 | 최후변론 중 |
| `item.analysis.result` | **요청자 본인만** | 분석 완료 |
| `system.notice` | 전원 | 아이템 사용 사실 등 |
| `game.titles` | 전원 | 칭호 생성 완료 |

> **`item.analysis.result`의 수신 범위는 [T6에서 확정되었다](결정필요사항.md) — 아이템을 사용한 본인만.** 다른 플레이어에게 전송하지 않는다.

### 2-3. 조간신문

```jsonc
// newspaper.preview — 마피아만, 밤 시작
{ "round": 2, "content": "어젯밤 마을은...", "editable": true }

// newspaper.publish — 전원, 낮 시작
{
  "round": 2,
  "content": "어젯밤 마을은... (사망자 템플릿 포함된 최종 본문)",
  "casualties": ["p4"],
  "displayDurationMs": 10000
}
```

- 사망자 고정 템플릿은 **서버가 본문에 삽입한 최종 결과**를 내려준다 (명세 17-7). 무사망 시 "평화로운 밤입니다"
- `casualties`는 UI 강조용으로 별도 제공
- `displayDurationMs`로 서버가 표시 시간을 통제 (명세 17-6, 13-5 서버 기준 원칙)

### 2-4. AI 심판 (스트리밍)

```jsonc
// judgment.started — 20-4의 얼굴 순차 표시용
{ "round": 2, "candidates": ["p1", "p2", "p3", "p5"], "durationMs": 30000 }

// judgment.reason — 사유 스트리밍 (여러 번)
{ "seq": 3, "text": "3번 플레이어는 2라운드에서 알리바이를 두 번 바꿨습니다. " }

// judgment.verdict — 마지막에 대상 발표 (명세 20-5)
{ "targetPlayerId": "p3", "executed": true, "defenseUsed": false }

// 방어권 발동 시 (명세 20-6)
{ "targetPlayerId": "p3", "executed": false, "defenseUsed": true,
  "defenseNotice": "대상자가 방어권을 발동해 처형되지 않았습니다." }

// judgment.failed — LLM 실패 폴백 (결정필요사항 T11)
{ "targetPlayerId": "p5", "fallback": "RULE_BASED" }
```

`judgment.reason`이 다 오기 전에 `judgment.verdict`를 보내지 않는다. 순서가 명세 20-5(사유 먼저, 대상 마지막)의 연출 계약이다.

### 2-5. 아이템 분석 결과

```jsonc
// item.analysis.result — 요청자 본인만
{
  "requestId": "req_a1b2",
  "itemCode": "STATEMENT_COMPARE",
  "targetPlayerId": "p3",
  "hasFindings": true,
  "result": {
    "summary": "2라운드와 3라운드의 진술이 충돌합니다.",
    "contradictions": [
      { "round": 2, "quote": "저는 어젯밤 아무 말도 안 했어요" },
      { "round": 3, "quote": "제가 어젯밤에 그 사람을 의심한다고 했잖아요" }
    ]
  }
}

// 키워드 뽑기
{ "requestId": "req_c3d4", "itemCode": "KEYWORD_EXTRACT", "targetPlayerId": "p3",
  "hasFindings": true, "result": { "keywords": ["알리바이", "의사", "3번", "투표 유도"] } }

// 발화 부족 / 모순 없음 — 정상 응답이다 (환각 방어, 설계문서 §7-5)
{ "requestId": "req_e5f6", "itemCode": "STATEMENT_COMPARE", "targetPlayerId": "p3",
  "hasFindings": false, "result": { "summary": "분석할 발언이 부족합니다." } }
```

`hasFindings: false`를 **에러로 취급하지 않는다.** 프론트가 정상 결과로 렌더링해야 한다.

```jsonc
// system.notice — 전원 (명세 15-5). 결과는 포함하지 않는다
{ "type": "ITEM_USED", "playerId": "p1", "itemCode": "KEYWORD_EXTRACT", "timestamp": 1721980000000 }
```

### 2-6. 얼굴 분석 브로드캐스트

```jsonc
// face.agitation — 전원
{ "playerId": "p3", "score": 62, "status": "OK" }

// 대상자 캠 꺼짐·이탈·얼굴 미검출
{ "playerId": "p3", "score": null, "status": "UNAVAILABLE" }
```

`status: UNAVAILABLE`이면 명세 6-4식 폴백 UI로 전환한다.

### 2-7. 칭호 (결산)

```jsonc
// game.ended — 전원, 게임 종료 즉시
{ "winner": "MAFIA", "players": [ { "playerId": "p1", "role": "MAFIA", "survived": true } ] }

// game.titles — 전원, LLM 완료 후 별도 push (2~5초 뒤)
{ "titles": [ { "playerId": "p2", "title": "최고의 팀킬상",
                "quote": "오 당신의 날카로운 칼날은 항상 당신의 동료만을 향하는군요" } ] }

// 실패 시 — 규칙 기반 폴백 칭호
{ "titles": [ ... ], "fallback": true }
```

**두 이벤트를 분리하는 이유:** 칭호를 기다렸다가 함께 보내면 결산 화면 진입이 2~5초 늦는다. 승패·역할을 먼저 띄우고 칭호를 나중에 채우면 로딩이 체감되지 않는다.

---

## 3. 에러 코드

| 코드 | HTTP | 상황 |
|---|---|---|
| `ITEM_ROLE_MISMATCH` | 403 | 시민 전용 아이템(진술대조권)을 마피아가 요청 |
| `ITEM_PHASE_INVALID` | 409 | 토론 시간이 아닐 때 사용 |
| `ITEM_TIME_INSUFFICIENT` | 409 | 토론 잔여 시간 부족 ([T8](결정필요사항.md)) |
| `ITEM_ALREADY_IN_USE` | 409 | 다른 액티브 아이템 사용 중 (명세 16-7) |
| `ITEM_NOT_OWNED` | 404 | 미보유 또는 이미 사용 완료 |
| `TARGET_NOT_ALIVE` | 409 | 지목 대상 사망 |
| `NEWSPAPER_TOO_LONG` | 400 | 1000자 초과 (명세 17-2) |
| `NEWSPAPER_NOT_EDITABLE` | 403 | 마피아 아님 / 밤 아님 / 조작권 미보유 |
| `AI_UNAVAILABLE` | 503 | LLM 호출 실패 — 아이템은 롤백 |

**LLM 실패 시 아이템 처리 원칙:** 사용 *요청* 단계 실패면 롤백(사용 가능), 요청이 수락된 뒤 실패면 [T7](결정필요사항.md) 결정에 따른다.

---

## 4. 재접속 복구 — AI 파트가 채울 필드

명세 3-3(토큰 검증 후 게임방 복귀)에서 **AI 산출물도 복구해야 한다.** 게임 스냅샷 API에 아래 필드가 필요하다.

```http
GET /api/v1/rooms/{roomId}/snapshot
```

```jsonc
{
  // ... 게임 로직 파트 필드 ...

  "newspaper": {                  // 권한에 따라 다르게 응답
    "round": 2,
    "content": "...",             // 마피아: 밤 편집 대상 / 전원: 낮 공개본 / 그 외: null
    "editable": false
  },
  "judgment": {                   // 심판 진행 중 재접속한 경우
    "inProgress": true,
    "reasonSoFar": "지금까지 스트리밍된 사유 전문",
    "verdict": null
  },
  "myItemResults": [ /* 본인이 받은 분석 결과 목록 */ ],
  "faceAgitation": { "playerId": "p3", "score": 58, "status": "OK" }
}
```

**주의:** `newspaper.content`는 요청자의 역할·현재 phase에 따라 응답이 달라진다. 밤에 시민이 조회하면 `null`이어야 한다.

---

## 5. 다른 파트에 요청할 것

AI 모듈이 동작하려면 게임 로직 쪽에서 아래를 제공해야 한다.

### 5-1. 서버 내부 phase 전환 훅

AI 모듈이 구독할 이벤트. **API가 아니라 서버 내부 이벤트**다.

| 시점 | AI 동작 |
|---|---|
| 낮 투표 시작 | 조간신문 생성 (명세 17-1) |
| 투표 집계 완료 → AI 심판 확정 | 심판 LLM 호출 |
| 밤 시작 | 마피아에게 신문 push |
| 낮 시작 | 전원에게 신문 publish |
| 게임 종료 | 칭호 생성 |

### 5-2. 게임 상태 조회 인터페이스

칭호 지표 집계와 결과 검증에 필요하다.

| 항목 | 용도 |
|---|---|
| 생존자·역할 목록 | 심판 대상 검증, 로그 가시성 판정 |
| 라운드별 투표 내역 | 칭호 지표 (팀킬 횟수, 투표 적중률) |
| 아이템 사용 이력 | 칭호 지표 |
| 사망 이벤트 (라운드·원인) | 조간신문 사망자 템플릿, 칭호 지표 |

### 5-3. WS 개별 전송 지원 확인

`item.analysis.result`(본인만)와 `newspaper.preview`(마피아만)는 **특정 대상 전송**이 필요하다. STOMP `user destination` 또는 세션 레지스트리 중 팀이 어느 방식을 쓰는지 먼저 확인해야 한다. 브로드캐스트만 가능한 구조면 이 두 기능이 성립하지 않는다.
