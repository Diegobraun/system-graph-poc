package demo;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@FeignClient("rates-service")
public interface RatesClient {

    @RequestMapping(method = RequestMethod.GET, value = "/rates/{currency}")
    Object rate(@PathVariable String currency);
}
