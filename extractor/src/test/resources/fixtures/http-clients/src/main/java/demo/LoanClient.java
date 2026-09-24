package demo;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "loan-service", url = "${services.loan-service.url}", path = "/loans")
public interface LoanClient {

    @GetMapping
    List<Object> byAccount(@RequestParam Long accountId);

    @PostMapping
    Object request(@RequestBody Object request);
}
