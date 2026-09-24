# graph-mcp-server

MCP server em **Spring AI 1.1** que expõe o grafo para assistentes de IA. Transporte Streamable HTTP em
`http://localhost:8090/mcp`.

```bash
mvn spring-boot:run
```

## Tools

| Tool | Parâmetros | Retorno |
|---|---|---|
| `list_services` | | serviços, commit, contagens |
| `service_overview` | `service` | endpoints (com quem chama), chamadas, tópicos (com o outro lado), dependentes, notas |
| `impact_of_change` | `service`, `contract` | serviços afetados com repositório, commit, arquivo e linha; campos GraphQL usados; problemas de payload; notas |
| `who_consumes` | `topic` | produtores, consumidores no código, consumidores vistos no Kafka |
| `compare_event_schemas` | `topic` | schemas dos dois lados e divergências campo a campo |
| `find_contract_issues` | | todos os problemas de integração do grafo |
| `record_note` | `targetType`, `target`, `text`, `author` | id da nota criada com status `pending` |
| `graph_model` | | labels, relações, propriedades e contagens |
| `read_cypher` | `query` | até 200 linhas, sessão somente leitura |

`contract` aceita:

| Formato | Exemplo | Responde |
|---|---|---|
| tópico Kafka | `account-opened` | consumidores e compatibilidade de payload |
| endpoint HTTP | `GET /accounts/{id}` ou `/accounts/{id}` | quem chama, e por qual client (`via`) |
| operação GraphQL | `QUERY customer` | quem chama e quais campos cada um seleciona |
| campo GraphQL | `Customer.monthlyIncome` | só os clientes cujo documento seleciona aquele campo |

## Severidade dos problemas de schema

| Severidade | Situação |
|---|---|
| `error` | mesmo campo com tipos diferentes nos dois lados |
| `warning` | consumidor espera um campo que o produtor não envia (chega `null`) |
| `info` | produtor envia um campo que o consumidor ignora |

## Estrutura

| Classe | Papel |
|---|---|
| `tools.SystemGraphTools` | Métodos `@Tool` registrados no MCP via `MethodToolCallbackProvider` |
| `graph.GraphClient` | Acesso ao Neo4j: leitura em sessão READ com timeout e limite de linhas, conversão de tipos |
| `graph.SchemaComparator` | Comparação de payload produtor x consumidor (com testes) |
| `graph.GraphQlAnalyzer` | Valida documento GraphQL do cliente contra o schema do servidor e lista os campos usados (com testes) |

## Configuração

| Propriedade | Padrão |
|---|---|
| `spring.neo4j.uri` | `${NEO4J_URI:bolt://localhost:7687}` |
| `spring.ai.mcp.server.protocol` | `STREAMABLE` |
| `spring.ai.mcp.server.instructions` | texto que diz ao assistente quando usar cada tool |
| `graph.query-timeout` | `5s` |
| `graph.max-rows` | `200` |

## Conectando clientes

Claude Code, pelo `.mcp.json` do repositório ou pela linha de comando:

```bash
claude mcp add --transport http system-graph http://localhost:8090/mcp
```

Sem assistente, para testar: `scripts/mcp-call.sh <tool> '<json>'` na raiz do projeto.
