package demo;

import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

@Controller
public class RatesController {

    @QueryMapping
    public List<Object> rates() {
        return List.of();
    }

    @MutationMapping(name = "transfer")
    public Boolean doTransfer(@Argument Double amount) {
        return true;
    }

    @SchemaMapping(typeName = "Query", field = "health")
    public String status() {
        return "UP";
    }
}
