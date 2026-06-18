package com.salonreview.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.salonreview.config.AiTriageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conditional bean factory for the Anthropic Java SDK client. The bean is only registered when
 * the feature flag is on AND an API key is configured — the rest of the AI module depends on the
 * bean's presence, so when the feature is off there is literally no AI code to misbehave.
 *
 * <p>Other components that may want to optionally call the LLM should depend on
 * {@code ObjectProvider<AnthropicClient>} rather than the bean directly, so they boot cleanly when
 * the feature is disabled.
 */
@Configuration
@ConditionalOnProperty(prefix = "ai.triage", name = "enabled", havingValue = "true")
public class AnthropicClientConfig {

    @Bean
    public AnthropicClient anthropicClient(AiTriageProperties props) {
        if (!props.isAnthropicConfigured()) {
            throw new IllegalStateException(
                    "ai.triage.enabled=true but ANTHROPIC_API_KEY is missing. "
                            + "Either set the key (see .env.example) or set AI_TRIAGE_ENABLED=false.");
        }
        return AnthropicOkHttpClient.builder()
                .apiKey(props.getAnthropicApiKey())
                .build();
    }
}
