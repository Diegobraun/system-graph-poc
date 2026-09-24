package com.example.account.loan;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "loan-service", url = "${services.loan-service.url}", path = "/loans")
public interface LoanClient {

    @GetMapping
    List<LoanSummary> byAccount(@RequestParam("accountId") Long accountId);
}
