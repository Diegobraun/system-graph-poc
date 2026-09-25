# system-graph-poc

Prova de conceito de **memória compartilhada entre serviços para assistentes de IA**.

Assistentes como Claude Code, Cursor e Copilot enxergam um repositório por vez. Num sistema com vários
serviços que conversam por REST, GraphQL e Kafka, o que mais importa fica *entre* os repositórios: quem chama
qual endpoint, quem consome qual evento, qual formato cada lado espera. Esta POC extrai essas informações do código,
guarda num grafo (Neo4j) e expõe o grafo para qualquer assistente via MCP (Model Context Protocol).

Na prática: um dev abre o `account-service`, pede para renomear um campo de um evento, e o assistente responde
que o `loan-service` quebra, em qual arquivo e em qual linha, sem ninguém ter aberto o outro repositório.

[![Demonstração da interface: entrada do grafo, detalhes de um serviço, problema de contrato e busca de impacto](docs/img/demo.gif)](docs/img/demo.mp4)

*Interface visual em http://localhost:8090: o grafo montando, os detalhes do payment-service, o erro de contrato
entre account e customer e o impacto de mudar o evento `account-opened`. Em resolução cheia: [demo.mp4](docs/img/demo.mp4).*

## Conteúdo

A POC tem um repositório por serviço, como seria na empresa:

| Repositório | Porta | O que é |
|---|---|---|
| **system-graph-poc** (este) | 8090 a 8093 | Plataforma: extrator, MCP server, interface visual, templates de CI, scripts e docs |
| [system-graph-account-service](https://github.com/Diegobraun/system-graph-account-service) | 8081 | Clientes e contas. REST, GraphQL (Spring for GraphQL), spring-kafka, Feign para loan e customer |
| [system-graph-loan-service](https://github.com/Diegobraun/system-graph-loan-service) | 8082 | Empréstimos. Spring Cloud Stream, `RestClient`, `@HttpExchange` e GraphQL para o account |
| [system-graph-customer-service](https://github.com/Diegobraun/system-graph-customer-service) | 8083 | KYC e perfil de risco. `@HttpExchange` para o account, `RestClient` para bureau e core-banking |
| [system-graph-payment-service](https://github.com/Diegobraun/system-graph-payment-service) | 8084 | Pix. GraphQL no account, `WebClient` no customer, `@HttpExchange` no fraud, `StreamBridge` |
| [system-graph-notification-service](https://github.com/Diegobraun/system-graph-notification-service) | 8085 | Notificações. Consome 6 tópicos, Feign no account, `@HttpExchange` no customer |
| [system-graph-investment-service](https://github.com/Diegobraun/system-graph-investment-service) | 8086 | CDB e LCI. `RestClient` no account, `@HttpExchange` no customer, publica `investment-applied` |
| [system-graph-fraud-service](https://github.com/Diegobraun/system-graph-fraud-service) | 8087 | Antifraude. Funções do Stream (`Function` com saída), GraphQL no account |

Todos os estilos de integração comuns em Spring Boot aparecem pelo menos uma vez: `RestClient`, `WebClient`,
Feign, `@HttpExchange`, client GraphQL, `KafkaTemplate`, `@KafkaListener`, funções do Spring Cloud Stream e
`StreamBridge`. Também há serviços que o grafo conhece sem ter o código: externos (bureau, pix-gateway,
sms-gateway, market-data), um legado só com OpenAPI (core-banking) e chamadores vistos só no APM
(internet-banking-bff, backoffice-portal).

Conteúdo deste repositório:

| Pasta | O que é |
|---|---|
| [`extractor`](extractor) | CLI Java que lê código compilado, fontes e `application.yml`, gera `service-graph.json` e grava no Neo4j |
| [`graph-mcp-server`](graph-mcp-server) | MCP server em Spring AI que responde perguntas sobre o grafo |
| [`ci`](ci) | Template de GitLab CI que cada serviço inclui para rodar a extração |
| [`scripts`](scripts) | Scripts para subir tudo, atualizar o grafo e rodar a demo |
| [`docs`](docs) | [Arquitetura e decisões](docs/architecture.md), [como levar para a empresa](docs/rollout-gitlab.md), [crawl local](docs/crawl.md), [grafo por área](docs/areas.md) e [fontes experimentais](docs/experimental.md) |

Cada serviço extrai só o próprio código, no próprio CI: o workflow `system-graph` de cada repositório baixa o
`graph-extractor.jar` da [release desta plataforma](https://github.com/Diegobraun/system-graph-poc/releases) e
gera o `service-graph.json` a cada push. O cruzamento entre serviços acontece no grafo central. Veja
[repositórios separados](docs/architecture.md#repositórios-separados).

Sem acesso ao pipeline, o mesmo grafo pode ser montado da máquina do dev com o [`crawl`](docs/crawl.md), que
passa por uma lista de repositórios, faz pull, compila, extrai e grava tudo de uma vez.

## Áreas

Os serviços estão divididos em três áreas de negócio, cada uma com o próprio Neo4j e o próprio MCP server. Um
quarto grafo, o hub, guarda só os contratos e as chamadas entre áreas. Detalhes em [docs/areas.md](docs/areas.md).

| Área | Serviços (time) | Neo4j | MCP e interface |
|---|---|---|---|
| contas | account-service (Contas Correntes), customer-service (Cadastro) | 7474 / 7687 | 8091 |
| credito | loan-service (Crédito), investment-service (Investimentos) | 7475 / 7688 | 8092 |
| pagamentos | payment-service (PIX), fraud-service (Prevenção a Fraude), notification-service (Comunicação) | 7476 / 7689 | 8093 |
| hub | todos, só a fronteira | 7477 / 7690 | 8090 |

O `.mcp.json` de cada serviço aponta para o MCP da própria área. Perguntado de dentro de contas, o
`impact_of_change` de `account-opened` traz o customer-service do grafo local e o loan-service e o
notification-service do hub, avisando que as áreas crédito e pagamentos precisam ser avisadas.

## Arquitetura

```mermaid
flowchart LR
    subgraph repos["Repositórios dos serviços"]
        A["account, customer,<br/>notification, investment<br/>spring-kafka"]
        L["loan, payment, fraud<br/>Spring Cloud Stream"]
    end

    subgraph pipeline["CI de cada serviço ou scripts/refresh-graph.sh"]
        X["graph-extractor extract"]
        I["graph-extractor ingest"]
        R["graph-extractor kafka-runtime"]
    end

    K[("Kafka")]
    N[("Neo4j da área")]
    HB[("Neo4j hub")]
    M["graph-mcp-server da área<br/>Spring AI MCP + UI"]
    C["Claude Code<br/>Cursor, Copilot..."]
    B["Navegador<br/>localhost:8091"]

    A -- "bytecode + fontes + yml" --> X
    L -- "bytecode + fontes + yml" --> X
    X -- "service-graph.json" --> I
    I -- "MERGE" --> N
    I -- "contratos e chamadas entre áreas" --> HB
    K -- "consumer groups" --> R
    R -- "OBSERVED_CONSUMING" --> N
    M -- "Cypher, sessão READ" --> N
    M -- "fronteira" --> HB
    C -- "MCP streamable HTTP /mcp" --> M
    B -- "/api/*" --> M
```

Três etapas independentes:

1. **Extração** (`extract`): roda depois do `mvn compile`. Lê anotações no bytecode (ClassGraph), cadeias de
   chamada no código-fonte (JavaParser), schemas e documentos GraphQL (graphql-java) e resolve propriedades do
   `application.yml`. Não sobe a aplicação.
2. **Ingestão** (`ingest`): grava o JSON no Neo4j da área e exporta a fronteira para o hub. Cada serviço
   substitui as próprias relações, então código removido some do grafo.
3. **Consulta** (MCP): o assistente chama tools como `impact_of_change` e `service_overview` no meio da tarefa.

Detalhes das regras de extração, do modelo do grafo e das decisões em [docs/architecture.md](docs/architecture.md).

## Fluxo de negócio dos serviços de exemplo

```mermaid
flowchart LR
    A["account-service"]
    L["loan-service"]
    C["customer-service"]
    P["payment-service"]
    N["notification-service"]
    I["investment-service"]
    F["fraud-service"]
    B(["bureau-service"])
    CB(["core-banking<br/>OpenAPI"])

    AO{{account-opened}}
    KYC{{customer-kyc-approved}}
    LD{{loan-disbursed}}
    PC{{payment-completed}}
    IA{{investment-applied}}
    FA{{fraud-alert}}

    A --> AO --> L & C & N
    C --> KYC --> A & L & I & N
    L --> LD --> A & C & F & N
    P --> PC --> A & F & N
    I --> IA --> A & N
    F --> FA --> N

    L -. "REST, GraphQL" .-> A
    L -. REST .-> C
    A -. Feign .-> L
    A -. Feign .-> C
    C -. "@HttpExchange" .-> A
    C -.-> B
    C -.-> CB
    P -. GraphQL .-> A
    P -. WebClient .-> C
    P -. "@HttpExchange" .-> F
    N -. Feign .-> A
    N -. "@HttpExchange" .-> C
    I -. REST .-> A
    I -. "@HttpExchange" .-> C
    F -. GraphQL .-> A
```

O `scripts/demo-flow.sh` percorre tudo com curl:

1. Cria a cliente no account-service e os contatos no customer-service.
2. Abre a conta, que nasce `PENDING_KYC`.
3. O customer-service consome `account-opened` e faz o KYC. A conta fica `ACTIVE` quando chega
   `customer-kyc-approved`.
4. Oferta pré-aprovada e análise de crédito no loan-service, com a faixa de risco vinda do customer-service.
5. Empréstimo, crédito na conta, Pix (com antifraude) e aplicação em CDB.
6. Confere o saldo final e as notificações que chegaram por 6 tópicos diferentes.

As dependências circulares (account e loan se chamam nos dois sentidos, account e customer também) são o tipo de
coisa que ninguém lembra que existe até algo quebrar, e aparecem direto no grafo (`DEPENDS_ON` nos dois sentidos).

## Como rodar

Pré-requisitos: Java 21+, Maven 3.9+, Docker.

```bash
git clone https://github.com/Diegobraun/system-graph-poc.git
cd system-graph-poc
scripts/clone-services.sh   # clona os 7 serviços como pastas irmãs (../system-graph-*-service)
scripts/start-all.sh        # Kafka + 4 Neo4j no Docker, depois os 7 serviços e os 4 MCP servers
scripts/demo-flow.sh        # executa o fluxo de negócio com curl
scripts/refresh-graph.sh    # crawl de cada área: pull, compila, extrai, grava na área e no hub
scripts/load-examples.sh    # opcional: contrato OpenAPI do core-banking e chamadas vistas por um APM
```

| O quê | Onde |
|---|---|
| Interface visual | http://localhost:8091 (contas), 8092 (credito), 8093 (pagamentos), 8090 (hub) |
| MCP server | `/mcp` nas mesmas portas |
| Serviços | http://localhost:8081 a 8087 (tabela acima) |
| Neo4j Browser | http://localhost:7474 a 7477 (neo4j / password123) |

Para parar: `scripts/stop-all.sh` (só as aplicações) ou `scripts/stop-all.sh --all` (derruba também o Docker).

## Interface visual

O `graph-mcp-server` também serve uma página (http://localhost:8091 para contas, 8090 para o hub) que desenha
o grafo e usa as mesmas consultas das tools do MCP:

![Mapa dos serviços](docs/img/ui-map.jpg)

- **Mapa**: serviços, tópicos e chamadas, com cor por tipo (HTTP, GraphQL, Kafka, runtime). Partículas correm na
  direção de cada chamada ou evento. Serviços tracejados não estão indexados; o core-banking tem só o contrato.
- **Problemas**: arestas com erro ficam vermelhas e as com aviso âmbar, pulsando. A aba Problemas lista tudo que
  o `find_contract_issues` encontra; clicar num item aproxima a câmera no trecho do grafo.
- **Detalhes**: clicar num serviço, tópico ou seta mostra endpoints, quem chama cada um, payloads e o
  `arquivo:linha` com link para o repositório no commit indexado.
- **Áreas**: serviços agrupados em caixas por área, com a área da instância em destaque. Os links no topo trocam
  entre as áreas e o hub.
- **Impacto**: escolha o serviço e o contrato no topo (tópico, `GET /accounts/{id}`, `QUERY customer`,
  `Customer.monthlyIncome`). O grafo apaga tudo que não é afetado.

![Impacto de mudar account-opened](docs/img/ui-impact.jpg)

É uma página estática (`graph-mcp-server/src/main/resources/static`), com Cytoscape.js via WebJar, sem build de
frontend e sem acesso à internet em runtime.

## Modo local para os seus projetos

O `refresh-graph.sh` roda o `crawl` com os arquivos de [`areas/`](areas), um por área. Para usar com os projetos do
trabalho, basta outro arquivo com a lista (caminho local ou URL do git) e rodar o jar da release:

```bash
java -jar graph-extractor.jar crawl --config ~/work/crawl.yml
```

Projetos Maven multi-módulo e Gradle são detectados sozinhos. O build padrão é `compile` offline primeiro, e
um projeto que falha não impede os outros. Detalhes, formato do arquivo e comparação com o modo CI em
[docs/crawl.md](docs/crawl.md).

Para quando nem compilar dá, ou o código não está disponível, há comandos experimentais separados: extração só
pelo código-fonte, pelo jar publicado no Nexus, importação de OpenAPI e de chamadas vistas pelo APM (Dynatrace ou
um JSON genérico). Ficam fora do caminho principal e estão em [docs/experimental.md](docs/experimental.md).

## O bug que o grafo encontra

O account-service e o loan-service têm, de propósito, uma divergência comum em sistemas sem schema registry. O `account-service`
publica `account-opened` com `accountId, customerId, openedAt`. O `loan-service` lê o mesmo evento esperando
`accountId, customerId, monthlyIncome`, e usa `monthlyIncome` para calcular a oferta pré-aprovada.

Nada quebra em runtime. O Jackson preenche `monthlyIncome` com `null`, e a oferta sai com limite zero. É fácil
ver no passo 5 do `demo-flow.sh`: a cliente tem renda de 8000, o esperado seriam 24000, e chega 0.

O grafo pega isso comparando as classes de payload dos dois lados, cada uma num repositório:

```bash
scripts/mcp-call.sh find_contract_issues
```

```json
{
  "severity": "warning",
  "area": "schema",
  "message": "com.example.loan.messaging.AccountOpenedEvent expects 'monthlyIncome' (BigDecimal) but com.example.account.messaging.AccountOpenedEvent never sends it, so it is always null"
}
```

O segundo bug é mais direto: o `KycClient` do account-service declara `GET /kyc/{customerId}/documents`, que o
customer-service não expõe. Em runtime só estoura quando alguém chama `/customers/{id}/documents`; no grafo,
aparece como erro (aresta vermelha na interface):

```json
{
  "severity": "error",
  "area": "http",
  "message": "account-service calls GET /kyc/{customerId}/documents on customer-service, but customer-service does not expose it"
}
```

Os dois serviços são da área contas, então essa chamada não sai para o hub: o erro só aparece no MCP de contas
(`MCP_URL=http://localhost:8091/mcp scripts/mcp-call.sh find_contract_issues`).

### GraphQL: validação do documento contra o schema de outro repositório

O loan-service consulta o account-service por GraphQL com o documento
[`customerProfile.graphql`](https://github.com/Diegobraun/system-graph-loan-service/blob/main/src/main/resources/graphql-documents/customerProfile.graphql). O grafo
guarda o schema do servidor e o documento do cliente, e o MCP server valida um contra o outro. Para ver
funcionando, remova `monthlyIncome` do [`schema.graphqls`](https://github.com/Diegobraun/system-graph-account-service/blob/main/src/main/resources/graphql/schema.graphqls),
rode `scripts/refresh-graph.sh` e depois `scripts/mcp-call.sh find_contract_issues`:

```text
error graphql - loan-service sends a GraphQL document that account-service rejects:
  Validation error (FieldUndefined@[customer/monthlyIncome]) : Field 'monthlyIncome' in type 'Customer' is undefined
```

Antes de remover, dá para perguntar quem usa o campo:

```bash
scripts/mcp-call.sh impact_of_change '{"service":"account-service","contract":"Customer.monthlyIncome"}'
```

## Usando com Claude Code

Cada repositório de serviço tem um `.mcp.json` apontando para o MCP server e um `CLAUDE.md` pedindo para
consultar o grafo antes de mexer em contratos. Com tudo rodando:

```bash
cd ../system-graph-account-service
claude
```

Na primeira vez o Claude Code pede para aprovar o servidor `system-graph` do `.mcp.json`. Depois é só trabalhar
normalmente. Trecho resumido de uma resposta real obtida nesta POC, de dentro do `account-service`, para o
pedido *"quero remover o campo monthlyIncome do tipo Customer no GraphQL, renomear GET /customers/{id} para
GET /clients/{id} e mudar o formato da resposta de GET /loans no loan-service; quem quebra, em qual repositório e
arquivo?"*:

> 1. **Remover `Customer.monthlyIncome` do GraphQL**: quebra o **loan-service**, em
>    `src/main/java/com/example/loan/client/CustomerProfileClient.java:20`, que seleciona esse campo na
>    `QUERY customer`. Recomendo marcar com `@deprecated` primeiro e remover depois da migração.
> 2. **Renomear `GET /customers/{id}`**: quebra o **loan-service**, em
>    `src/main/java/com/example/loan/client/CustomerApi.java:10` (confiança média). Há uma nota do time, pendente
>    de confirmação: o loan-service usa o `monthlyIncome` da resposta, multiplicado por 5, para calcular o valor
>    máximo do empréstimo.
> 3. **Mudar a resposta de `GET /loans`**: quebra o **account-service**, em
>    `src/main/java/com/example/account/loan/LoanClient.java:11`, um client Feign (confiança alta).
>
> Os dois serviços dependem um do outro, então cada mudança exige alterar os dois lados.

O assistente estava aberto só no account-service e mesmo assim apontou arquivos do loan-service, que ele não
enxerga. A resposta de cada tool traz o `repository` e o `commitSha` de cada serviço afetado, para abrir o
arquivo no repositório certo.

Outros clientes MCP (Cursor, VS Code com Copilot, Claude Desktop) usam a URL do MCP da área, por exemplo `http://localhost:8091/mcp`.

## Tools do MCP

| Tool | Para quê |
|---|---|
| `list_services` | Serviços conhecidos, commit extraído e contagem de endpoints e tópicos |
| `service_overview` | Tudo sobre um serviço: endpoints e quem chama, chamadas que faz, tópicos, dependentes, notas |
| `impact_of_change` | Quem é afetado, em qual repositório e arquivo, por mudar um endpoint (`GET /accounts/{id}`), evento (`account-opened`), operação GraphQL (`QUERY customer`) ou campo GraphQL (`Customer.monthlyIncome`) |
| `who_consumes` | Produtores, consumidores declarados no código e consumidores observados no Kafka |
| `compare_event_schemas` | Compara campo a campo o payload do produtor e dos consumidores |
| `find_contract_issues` | Varredura geral: endpoint ou operação inexistente, documento GraphQL inválido, tópico sem produtor, payload divergente, consumidor oculto |
| `record_note` | Grava regra de negócio ou pegadinha ligada a serviço, tópico ou endpoint (status `pending`) |
| `list_areas` | Áreas, times, serviços e quantos contratos de cada área outras áreas usam |
| `graph_model` | Descreve labels, relações e propriedades do grafo |
| `read_cypher` | Cypher livre, somente leitura, limitado a 200 linhas |

Para testar sem assistente: `scripts/mcp-call.sh <tool> '<json>'`, por exemplo
`scripts/mcp-call.sh impact_of_change '{"service":"account-service","contract":"GET /accounts/{id}"}'`. O padrão é o hub; para
uma área, `MCP_URL=http://localhost:8091/mcp scripts/mcp-call.sh ...`.

## Explorando no Neo4j Browser

Abra http://localhost:7474 (contas; 7475 credito, 7476 pagamentos, 7477 hub) e rode:

```cypher
MATCH (n) WHERE NOT n:Schema RETURN n
```

```cypher
MATCH (p:Service)-[:PUBLISHES]->(t:Topic)<-[:CONSUMES]-(c:Service)
RETURN p.name AS producer, t.name AS topic, c.name AS consumer
```

```cypher
MATCH (caller:Service)-[r:CALLS]->(e:Endpoint)<-[:EXPOSES]-(owner:Service)
RETURN caller.name, e.method, e.path, owner.name, r.location
```

## Limitações conhecidas

- **Profiles do Spring** são ignorados. Só vale o `application.yml` base (documentos com
  `spring.config.activate.on-profile` são descartados).
- **URL montada em runtime** (`"/accounts/" + id`, `UriComponentsBuilder` complexo) não é resolvida. A chamada
  aparece como dependência de serviço, sem endpoint, ou pode ser declarada no `system-graph.yml`.
- **Clients injetados de outro lugar**: o `baseUrl` é encontrado na própria classe, num `@Bean` com o mesmo
  nome do campo ou no método que chama `createClient(...)`. Configurações mais indiretas precisam do manifesto.
- **`@ImportHttpServices`** (Spring Framework 7 / Boot 4), que define a URL por grupo em propriedades, ainda não
  é lido. O projeto usa Boot 3.5, onde o padrão é `HttpServiceProxyFactory`.
- **Payload aninhado**: a comparação de payload Kafka olha só o primeiro nível de campos do DTO. Em GraphQL a
  validação é completa, porque o schema é explícito.
- **Documento GraphQL montado em runtime** (string concatenada com variáveis Java) não é lido. Documentos em
  arquivo, constantes e text blocks são.
- A comparação de tipos é por nome simples (`Long`, `BigDecimal`), não por compatibilidade de JSON.
- **Um serviço por projeto**: em repositório com várias aplicações, o extrator usa o primeiro
  `spring.application.name` e avisa. Cada módulo de aplicação precisa entrar no arquivo de crawl separado.
- **Kotlin**: as classes compiladas entram, mas o código-fonte Kotlin não é lido (cadeias de `RestClient`,
  `KafkaTemplate` etc. ficam de fora).

## Stack

Spring Boot 3.5.16, Spring Cloud 2025.0.3, Spring AI 1.1.8, Neo4j 5.26 Community, Kafka 3.9 (KRaft),
Spring for GraphQL 1.4, Spring Cloud OpenFeign 4.3, ClassGraph 4.8, JavaParser 3.28, graphql-java 24, Java 21.
