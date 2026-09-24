package demo;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.HttpExchange;

public interface WalletApi {

    @HttpExchange(method = "GET", url = "/wallets/{id}")
    Object get(@PathVariable Long id);
}
