package demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

public class ProfileClient {

    private final RestClient restClient;

    public ProfileClient(RestClient.Builder builder, @Value("${services.profile-service.url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public Object get(Long id) {
        return restClient.get().uri("/profiles/{id}", id).retrieve().body(Object.class);
    }
}
