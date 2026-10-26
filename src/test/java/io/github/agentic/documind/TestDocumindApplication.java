package io.github.agentic.documind;

import org.springframework.boot.SpringApplication;

public class TestDocumindApplication {

	public static void main(String[] args) {
		SpringApplication.from(DocumindApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
