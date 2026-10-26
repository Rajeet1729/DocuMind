package io.github.agentic.documind;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.testcontainers.service.connection.Ssl;
import org.springframework.context.annotation.Bean;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	@Ssl
	ElasticsearchContainer elasticsearchContainer() {
		return new ElasticsearchContainer(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.3.3"));
	}

}
