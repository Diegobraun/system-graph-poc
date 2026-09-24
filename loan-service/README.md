# loan-service

Serviço de empréstimos. Usa **Spring Cloud Stream** (função `Consumer` e `StreamBridge`) para Kafka e
**`RestClient`** para chamar o account-service.

## Contratos

| Tipo | Contrato | Detalhe |
|---|---|---|
| REST exposto | `POST /loans` | pede empréstimo: `accountId`, `amount`, `installments` |
| REST exposto | `GET /loans/{id}` | consulta empréstimo |
| REST exposto | `GET /offers/{accountId}` | oferta pré-aprovada criada ao abrir a conta |
| REST chamado | `GET /accounts/{id}` no account-service | status da conta e `customerId` |
| REST chamado | `GET /customers/{id}` no account-service | renda mensal para o limite |
| Kafka consome | `account-opened` | função `accountOpened`, cria a oferta pré-aprovada |
| Kafka publica | `loan-disbursed` | `StreamBridge` no binding `loanDisbursed-out-0` |

## Regras

- Empréstimo aprovado se a conta está `ACTIVE` e o valor é no máximo 5 vezes a renda mensal.
- Oferta pré-aprovada: 3 vezes a renda mensal que chega no evento `account-opened`.

## A divergência proposital

`AccountOpenedEvent` deste serviço tem `monthlyIncome`, mas o account-service não envia esse campo. O
Jackson preenche `null` e a oferta sai com limite zero, sem erro nenhum. O `find_contract_issues` do MCP
server aponta isso. Para corrigir, o account-service passaria a enviar a renda no evento, ou o loan-service
buscaria a renda via `GET /customers/{id}` como já faz no pedido de empréstimo.

## Manifesto

[`system-graph.yml.example`](system-graph.yml.example) mostra como declarar dependências que a análise estática
não consegue ver. Renomeado para `system-graph.yml`, o extrator soma essas entradas ao grafo com
`source: manifest`.

## Rodando sozinho

```bash
mvn spring-boot:run
```

Porta 8082. Precisa do Kafka e do account-service em `localhost:8081`.
