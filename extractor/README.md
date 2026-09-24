# graph-extractor

CLI que transforma um serviço Spring Boot num `service-graph.json` e grava esse JSON no Neo4j. Não sobe a
aplicação e não precisa de nada além do projeto compilado.

```bash
mvn package
java -jar target/graph-extractor.jar <comando>
```

## Comandos

### extract

```bash
java -jar target/graph-extractor.jar extract --project ../loan-service [--out arquivo.json]
```

Lê `target/classes` ou `build/classes/java/main` (precisa compilar antes), de todos os módulos quando o projeto
é Maven multi-módulo ou Gradle multi-projeto, `src/main/java`, `src/main/resources/application*.yml`,
schemas GraphQL em `src/main/resources/graphql`, documentos em `src/main/resources/graphql-documents` e o
`system-graph.yml` opcional. Por padrão escreve em `<projeto>/target/service-graph.json`. Chamadas cujo serviço
alvo não foi identificado aparecem como aviso no console.

### ingest

```bash
java -jar target/graph-extractor.jar ingest ../account-service/target/service-graph.json ../loan-service/target/service-graph.json
```

Cria as constraints na primeira execução e grava cada serviço numa transação, substituindo as relações
anteriores dele.

### kafka-runtime

```bash
java -jar target/graph-extractor.jar kafka-runtime --bootstrap localhost:9092
```

Lista os consumer groups do cluster, com membros ativos e offsets commitados, e grava
`(:Service)-[:OBSERVED_CONSUMING]->(:Topic)`. Grupos internos e anônimos são ignorados.

### crawl

```bash
java -jar target/graph-extractor.jar crawl --config ../crawl.yml [--only a,b] [--no-pull] [--no-build] [--no-ingest]
```

Para cada projeto do arquivo: clona ou faz `git pull --ff-only`, compila (`mvn compile` ou `gradle classes`,
offline primeiro), extrai e grava `<output>/<serviço>.json`. No final faz o `ingest` de todos que deram certo e,
se o arquivo tiver `kafka.bootstrap`, o `kafka-runtime`. Veja [docs/crawl.md](../docs/crawl.md).

### experimental

```bash
java -jar target/graph-extractor.jar experimental <extract-source|extract-jar|import-openapi|import-observed-calls|import-dynatrace> ...
```

Fontes alternativas, fora do caminho principal. Veja [docs/experimental.md](../docs/experimental.md).

Conexão com o Neo4j em todos os comandos: `--neo4j-uri`, `--neo4j-user`, `--neo4j-password` ou as variáveis
`NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD`. O padrão é o Neo4j do `docker-compose.yml`.

## Formato do service-graph.json

```json
{
  "service": "loan-service",
  "repository": "https://gitlab.empresa/credito/loan-service",
  "commitSha": "a1b2c3d",
  "exposes": [{ "method": "POST", "path": "/loans", "handler": "com.example.loan.loan.LoanController#request:29" }],
  "calls": [{
    "targetService": "account-service", "method": "GET", "path": "/accounts/{id}",
    "baseUrl": "${services.account-service.url}", "via": "rest-client", "source": "static", "confidence": "medium",
    "location": "src/main/java/com/example/loan/client/AccountClient.java:17"
  }, {
    "targetService": "account-service", "method": "QUERY", "path": "customer", "via": "graphql",
    "document": "query customerProfile($id: ID!) { customer(id: $id) { name monthlyIncome } }"
  }],
  "publishes": [{ "topic": "loan-disbursed", "via": "stream-bridge", "payloadType": "com.example.loan.messaging.LoanDisbursedEvent" }],
  "consumes": [{ "topic": "account-opened", "via": "stream-function", "group": "loan-service", "payloadType": "com.example.loan.messaging.AccountOpenedEvent" }],
  "schemas": [{ "className": "com.example.loan.messaging.AccountOpenedEvent", "fields": [{ "name": "monthlyIncome", "type": "BigDecimal" }] }],
  "graphqlSchema": null
}
```

O JSON é o contrato entre extração e ingestão. Dá para gerar esse arquivo por outros meios (outra linguagem,
outra ferramenta) e reaproveitar o `ingest`.

## Estrutura

| Classe | Papel |
|---|---|
| `scan.Extractor` | Orquestra a extração e junta tudo no `ServiceGraph` |
| `scan.ProjectLayout` | Descobre módulos Maven e Gradle e as pastas de classes, fontes e resources |
| `scan.AnnotationSource` | Interface de leitura das anotações; `AnnotationScanner.bytecode()` é a padrão |
| `scan.AnnotationScanner` | ClassGraph: controllers, `@FeignClient`, `@HttpExchange`, `@QueryMapping` e afins, `@KafkaListener`, beans funcionais do Stream, campos dos DTOs |
| `scan.SourceScanner` | JavaParser: cadeias `RestClient`/`WebClient`, `createClient(...)`, clients GraphQL, `KafkaTemplate.send`, `StreamBridge.send`, `baseUrl`, linha de cada método |
| `scan.GraphQlScanner` | graphql-java: operações raiz do schema e dos documentos dos clients |
| `scan.SpringProperties` | Carrega `application.properties`/`.yml` e resolve `${...}` |
| `scan.TargetServiceResolver` | Descobre o serviço alvo a partir da URL ou do nome da propriedade |
| `scan.Manifest` | Lê o `system-graph.yml` |
| `ingest.GraphIngestor` | Grava no Neo4j |
| `runtime.KafkaRuntimeInspector` | Lê consumer groups via `AdminClient` |
| `crawl.CrawlConfig`, `crawl.Crawler` | Lê o `crawl.yml` e roda git, build e extração projeto a projeto |
| `crawl.GitSync`, `crawl.BuildRunner`, `crawl.CommandRunner` | Clone/pull, escolha do comando de build, processos com timeout e log |
| `experimental.source.SourceOnlyAnnotationSource` | Anotações lidas só do código-fonte, sem compilar |
| `experimental.jar.JarProject` | Abre um jar (fat jar ou comum), bibliotecas selecionadas e `-sources.jar` |
| `experimental.openapi.OpenApiImporter` | Importa uma spec OpenAPI/Swagger como endpoints de um serviço |
| `experimental.observed.*` | Chamadas vistas em runtime: JSON genérico e API do Dynatrace |

## Testes

`HttpClientExtractionTest` e `GraphQlExtractionTest` compilam projetos de exemplo em
[`src/test/resources/fixtures`](src/test/resources/fixtures) dentro do próprio teste e rodam a extração completa
sobre eles. Cobrem cadeias `RestClient`, Feign com e sem `url`, `@HttpExchange` com `baseUrl` no `@Bean`, via
`@Qualifier` e com URL absoluta, schema GraphQL com `extend type` e `@SchemaMapping`, e documentos GraphQL inline e
em arquivo.

`ProjectLayoutTest` cobre Maven multi-módulo e Gradle, `CrawlerTest` roda o crawl sobre projetos temporários e confere
que falhas de git, build e extração num projeto não impedem os outros, e `SourceOnlyParityTest` exige que a extração só por código-fonte dê
o mesmo resultado da extração por bytecode em todas as fixtures. `JarProjectTest` monta um fat jar a partir de
uma fixture e confere o resultado com e sem fontes, com biblioteca selecionada e a proteção contra entradas
`../` no zip. O cliente do Dynatrace é testado contra um servidor HTTP local com paginação.

As regras de extração estão detalhadas em [docs/architecture.md](../docs/architecture.md#regras-de-extração).
