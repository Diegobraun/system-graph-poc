package demo;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange("/accounts")
public interface AccountApi {

    @GetExchange("/{id}")
    Object get(@PathVariable Long id);

    @PostExchange
    Object open(@RequestBody Object request);
}
