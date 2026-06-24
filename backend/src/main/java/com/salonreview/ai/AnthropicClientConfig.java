package com.salonreview.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.salonreview.config.AiTriageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conditional bean factory for the Anthropic Java SDK client, shared by every AI feature. The bean
 * is registered when EITHER {@code ai.triage.enabled} OR {@code rag.enabled} is on (both call Claude
 * via the same {@code ANTHROPIC_API_KEY}); when both are off there is literally no AI code to
 * misbehave. The key is read from {@link AiTriageProperties} because it is always bound from the
 * {@code ANTHROPIC_API_KEY} env var regardless of which feature flag is set.
 *
 * <p>Other components that may want to optionally call the LLM should depend on
 * {@code ObjectProvider<AnthropicClient>} rather than the bean directly, so they boot cleanly when
 * all AI features are disabled.
 */
@Configuration
@ConditionalOnExpression("${ai.triage.enabled:false} or ${rag.enabled:false}")
public class AnthropicClientConfig {

    @Bean
    public AnthropicClient anthropicClient(AiTriageProperties props) {
        if (!props.isAnthropicConfigured()) {
            throw new IllegalStateException(
                    "An AI feature is enabled (ai.triage.enabled or rag.enabled) but ANTHROPIC_API_KEY "
                            + "is missing. Either set the key (see .env.example) or disable the feature.");
        }
        return AnthropicOkHttpClient.builder()
                .apiKey(props.getAnthropicApiKey())
                .build();
    }
}
