package com.ssafy.mafia.ailab.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * GMS 연결 확인 — 프롬프트 작업 전에 이 테스트가 통과해야 한다.
 *
 * <p>ai-lab 은 DB 의존성이 없어 {@code @SpringBootTest} 로 전체 컨텍스트를 올려도 가볍다.
 * 팀 레포로 옮긴 뒤에는 MySQL·Redis 에 붙으려다 실패하므로 범위를 좁혀야 한다.
 *
 * <p>실행: {@code ./gradlew test --tests "*GmsSmokeTest*"}
 */
@SpringBootTest
class GmsSmokeTest {

    @Autowired
    ChatClient.Builder chatClientBuilder;

    @Value("${spring.ai.openai.base-url}")
    String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    String apiKey;

    @Test
    @DisplayName("1. 설정이 제대로 바인딩됐는가")
    void 설정_확인() {
        System.out.println("base-url : " + baseUrl);
        System.out.println("api-key  : " + mask(apiKey));

        // base-url 에 /v1 이 빠지면 런타임에 404 만 보이고 원인이 드러나지 않는다.
        assertThat(baseUrl)
                .as("GMS 는 업스트림 호스트를 경로에 끼워넣고, SDK 가 /chat/completions 만 덧붙인다")
                .endsWith("/api.openai.com/v1");
        assertThat(apiKey)
                .as(".env 의 GMS_KEY 가 비어 있으면 여기서 걸린다")
                .isNotBlank();
    }

    @Test
    @DisplayName("2. 한글이 깨지지 않는가")
    void 인코딩_확인() {
        String korean = "강예린 씨가 제일 수상해요";
        System.out.println("한글 출력 : " + korean);

        assertThat(korean).hasSize(14);
        assertThat(System.getProperty("file.encoding"))
                .as("build.gradle 의 인코딩 설정이 먹었는지")
                .isEqualToIgnoringCase("UTF-8");
    }

    @Test
    @DisplayName("3. GMS 를 통해 실제로 응답이 오는가")
    void 평문_호출() {
        long t = System.currentTimeMillis();
        String answer = chatClientBuilder.build()
                .prompt()
                .user("핑이라고만 답해")
                .call()
                .content();
        long elapsed = System.currentTimeMillis() - t;

        System.out.printf("응답 (%dms) : %s%n", elapsed, answer);
        assertThat(answer).isNotBlank();
    }

    @Test
    @DisplayName("4. 한글 프롬프트가 왕복해도 멀쩡한가")
    void 한글_왕복() {
        String answer = chatClientBuilder.build()
                .prompt()
                .system("너는 한국어로만 답한다. 사용자가 준 단어를 그대로 되돌려준다.")
                .user("이 단어를 그대로 다시 말해줘: 시간 낭비")
                .call()
                .content();

        System.out.println("한글 왕복 : " + answer);

        // 요청이 깨져서 갔다면 이 단어가 돌아올 수 없다.
        assertThat(answer).contains("시간");
    }

    private static String mask(String key) {
        if (key == null || key.length() < 20) {
            return "(없음 또는 너무 짧음)";
        }
        return key.substring(0, 12) + "..." + key.substring(key.length() - 4);
    }
}
