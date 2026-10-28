package io.github.agentic.documind;

import java.time.Duration;

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
				.withEnv("xpack.security.enabled", "false")
				// Each @SpringBootTest with a distinct context starts its own ES container,
				// so a full-suite run boots several back to back. Pin a small fixed heap
				// (mirrors compose.yaml) so they coexist on a modest machine, and give the
				// "started" log-wait generous headroom so a slow boot under load does not
				// flake the suite — the default 60s was exceeded with two containers running.
				.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
				.withStartupTimeout(Duration.ofMinutes(3));
	}

}
