package io.github.agentic.documind.application.service;

import io.github.agentic.documind.application.port.out.ChunkStore;
import io.github.agentic.documind.application.port.out.ReasoningModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code documind.retrieval.agentic.enabled} selects exactly one {@link RetrievalStrategy},
 * and the default (property absent) is single-shot — the Slice-3 behaviour.
 */
class RetrievalStrategyWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ChunkStore.class, () -> mock(ChunkStore.class))
            .withBean(ReasoningModel.class, () -> mock(ReasoningModel.class))
            .withUserConfiguration(SingleShotRetrieval.class, AgenticRetrieval.class);

    @Test
    void defaultIsSingleShot() {
        runner.run(context -> assertThat(context).getBean(RetrievalStrategy.class).isInstanceOf(SingleShotRetrieval.class));
    }

    @Test
    void explicitFalseIsSingleShot() {
        runner.withPropertyValues("documind.retrieval.agentic.enabled=false")
                .run(context -> assertThat(context).getBean(RetrievalStrategy.class).isInstanceOf(SingleShotRetrieval.class));
    }

    @Test
    void trueIsAgentic() {
        runner.withPropertyValues("documind.retrieval.agentic.enabled=true")
                .run(context -> assertThat(context).getBean(RetrievalStrategy.class).isInstanceOf(AgenticRetrieval.class));
    }
}
