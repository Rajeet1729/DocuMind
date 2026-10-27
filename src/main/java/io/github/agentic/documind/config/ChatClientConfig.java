package io.github.agentic.documind.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI auto-configures a {@link ChatClient.Builder} (from the OpenAI chat model),
 * but not a ready {@link ChatClient}. Build one here so services can inject the client
 * directly rather than each holding a builder.
 */
@Configuration(proxyBeanMethods = false)
class ChatClientConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
