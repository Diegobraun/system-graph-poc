# account-service

Serviço de clientes e contas. Usa **spring-kafka** direto (`KafkaTemplate` e `@KafkaListener`), expõe REST e
**GraphQL** (Spring for GraphQL) e chama o loan-service com **OpenFeign**.

## Contratos

| Tipo | Contrato | Detalhe |
|---|---|---|
| REST exposto | `POST /customers` | cria cliente com `name`, `document`, `monthlyIncome` |
| REST exposto | `GET /customers/{id}` | chamado pelo loan-service |
| REST exposto | `POST /accounts` | abre conta para um cliente e publica `account-opened` |
| REST exposto | `GET /accounts/{id}` | chamado pelo loan-service |
| REST exposto | `GET /accounts/{id}/summary` | saldo e empréstimos da conta, busca os empréstimos no loan-service |
| GraphQL exposto | `query customer(id)` / `query account(id)` | schema em [`schema.graphqls`](src/main/resources/graphql/schema.graphqls); `Customer.accounts` resolvido por `@SchemaMapping` |
| REST chamado | `GET /loans?accountId=` no loan-service | `LoanClient`, `@FeignClient(name = "loan-service")` |
| Kafka publica | `account-opened` | `AccountOpenedEvent(accountId, customerId, openedAt)` via `KafkaTemplate` |
| Kafka consome | `loan-disbursed` | `LoanDisbursedEvent`, credita o valor na conta (idempotente por `loanId`) |

GraphQL fica em `http://localhost:8081/graphql`.

O tópico publicado vem de uma constante (`Topics.ACCOUNT_OPENED`) e o consumido de uma propriedade
(`${app.topics.loan-disbursed}`). Os dois casos são de propósito, para exercitar o extrator.

## Configuração relevante

- `spring.application.name: account-service`: identifica o serviço no grafo.
- `spring.kafka.consumer.group-id: account-service`: mesmo nome, para cruzar com o runtime do Kafka.
- `spring.json.add.type.headers: false`: o evento não carrega o nome da classe Java, então o consumidor usa a
  própria classe. É isso que permite a divergência de payload com o loan-service passar despercebida em runtime.

## Rodando sozinho

```bash
mvn spring-boot:run
```

Porta 8081. Precisa do Kafka em `localhost:9092` (`docker compose up -d` na raiz).

## Integração com IA

`.mcp.json` aponta para o MCP server do grafo e o `CLAUDE.md` orienta o assistente a consultar
`impact_of_change` antes de mexer em contratos.
