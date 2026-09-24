package mm.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "pricing-service")
public interface PricingClient {

    @GetMapping("/prices/{sku}")
    Object price(@PathVariable String sku);
}
