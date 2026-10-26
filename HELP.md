# Getting Started

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.1.2/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.1.2/maven-plugin/build-image.html)
* [Spring Boot Testcontainers support](https://docs.spring.io/spring-boot/4.1.2/reference/testing/testcontainers.html#testing.testcontainers)
* [Testcontainers Elasticsearch Container Reference Guide](https://java.testcontainers.org/modules/elasticsearch/)
* [Spring Web](https://docs.spring.io/spring-boot/4.1.2/reference/web/servlet.html)
* [OpenAI](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)
* [Elasticsearch Vector Database](https://docs.spring.io/spring-ai/reference/api/vectordbs/elasticsearch.html)
* [Spring Boot Actuator](https://docs.spring.io/spring-boot/4.1.2/reference/actuator/index.html)
* [Docker Compose Support](https://docs.spring.io/spring-boot/4.1.2/reference/features/dev-services.html#features.dev-services.docker-compose)
* [Testcontainers](https://java.testcontainers.org/)

### Guides
The following guides illustrate how to use some features concretely:

* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)
* [Building a RESTful Web Service with Spring Boot Actuator](https://spring.io/guides/gs/actuator-service/)

### Docker Compose support
This project contains a Docker Compose file named `compose.yaml`.
In this file, the following services have been defined:

* elasticsearch: [`docker.elastic.co/elasticsearch/elasticsearch:9.3.3`](https://www.docker.elastic.co/r/elasticsearch)

Please review the tags of the used images and set them to the same as you're running in production.

### Testcontainers support

This project uses [Testcontainers at development time](https://docs.spring.io/spring-boot/4.1.2/reference/features/dev-services.html#features.dev-services.testcontainers).

Testcontainers has been configured to use the following Docker images:

* [`docker.elastic.co/elasticsearch/elasticsearch:9.3.3`](https://www.docker.elastic.co/r/elasticsearch)

Please review the tags of the used images and set them to the same as you're running in production.

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.

