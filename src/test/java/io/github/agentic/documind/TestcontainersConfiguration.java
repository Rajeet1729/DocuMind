package io.github.agentic.documind;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers infrastructure. The Elasticsearch container runs with security
 * disabled, mirroring {@code compose.yaml}, so tests connect over plain HTTP with no
 * credentials — @{@link ServiceConnection} wires the uris into the Spring AI vector
 * store. Public so adapter tests in other packages can import it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	ElasticsearchContainer elasticsearchContainer() {
		return new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.3.3"))
				.withEnv("xpack.security.enabled", "false");
	}

}
