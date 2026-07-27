package com.ssafy.mafia.ailab.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link ChatClient} 빈 등록.
 *
 * <p>{@code spring-ai-starter-model-openai} 의존성이 있으면 {@code ChatClient.Builder} 가
 * {@code application.properties}(base-url, api-key, model 등)를 읽어 자동으로 빈이 된다.
 * 그 Builder 를 한 번만 {@code build()} 해서 {@code ChatClient} 로 등록하고,
 * 쓰는 쪽에서는 Builder 가 아니라 {@code ChatClient} 를 주입받는다.
 *
 * <p>호출마다 {@code build()} 하지 않는 이유는 인스턴스를 매번 새로 만들 필요가 없기 때문이고,
 * 팀·강사님 예제가 이 방식을 쓴다.
 *
 * <p>기능별로 모델·파라미터가 달라야 하는 경우는 빈을 여러 개 만들지 않고
 * 호출 시 {@code .options(OpenAiChatOptions.builder()...build())} 로 덮어쓴다
 * → docs/LLM호출설정.md §4
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
