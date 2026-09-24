package demo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class ClientsConfig {

    @Bean
    AccountApi accountApi(RestClient.Builder builder, @Value("${services.account-service.url}") String baseUrl) {
        RestClient client = builder.baseUrl(baseUrl).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(AccountApi.class);
    }

    @Bean
    PaymentsApi paymentsApi(RestClient.Builder builder) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build())).build().createClient(PaymentsApi.class);
    }

    @Bean
    RestClient walletRestClient(RestClient.Builder builder) {
        return builder.baseUrl("http://wallet-service").build();
    }

    @Bean
    WalletApi walletApi(@Qualifier("walletRestClient") RestClient client) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(WalletApi.class);
    }
}
