# Arquitetura e decisões

## Duas camadas de memória

O grafo guarda dois tipos de informação, com origens e confiabilidade diferentes.

**Estrutura (derivada do código).** Serviços, endpoints, chamadas REST, tópicos Kafka, payloads. Ninguém
escreve isso à mão: o extrator gera a cada build. Se o código muda, o grafo muda junto.

**Conhecimento (escrito por pessoas e pela IA).** Regras de negócio, decisões e pegadinhas que não aparecem no
código, ou aparecem de forma espalhada. Ficam em nós `Note` ligados ao elemento a que se referem. Entram com
status `pending` e alguém do time aprova ou descarta. Uma nota ligada a `account-service GET /customers/{id}`
aparece para quem estiver mexendo nesse endpoint no account-service e também para quem estiver mexendo no
client que chama esse endpoint no loan-service.

## Modelo do grafo

```mermaid
flowchart LR
    SA["Service<br/>account-service"]
    SL["Service<br/>loan-service"]
    E["Endpoint<br/>account-service GET /accounts/{}"]
    T["Topic<br/>account-opened"]
    SP["Schema<br/>producer"]
    SC["Schema<br/>consumer"]
    N["Note"]

    SA -- EXPOSES --> E
    SL -- CALLS --> E
    SL -- DEPENDS_ON --> SA
    SA -- PUBLISHES --> T
    SL -- CONSUMES --> T
    SL -- OBSERVED_CONSUMING --> T
    SA -- DEFINES --> SP
    SL -- DEFINES --> SC
    T -- HAS_SCHEMA --> SP
    T -- HAS_SCHEMA --> SC
    N -- ABOUT --> E
```

| Nó | Chave | Propriedades |
|---|---|---|
| `Service` | `name` (= `spring.application.name`) | `indexed`, `commitSha`, `extractedAt`, `ingestedAt` |
| `Endpoint` | `key` = `serviço MÉTODO /path/{}` | `service`, `method`, `path` |
| `Topic` | `name` | |
| `Schema` | `key` = `serviço\|tópico\|lado\|classe` | `side` (producer, consumer), `className`, `fieldNames[]`, `fieldTypes[]` |
| `Note` | `id` | `text`, `author`, `status`, `createdAt` |

| Relação | Propriedades |
|---|---|
| `EXPOSES` | `handler` (classe#método:linha) |
| `CALLS` | `location` (arquivo:linha), `confidence`, `source`, `baseUrl` |
| `DEPENDS_ON` | `source` |
| `PUBLISHES` / `CONSUMES` | `via`, `payloadType`, `location`, `source`; `CONSUMES` também tem `group` |
| `OBSERVED_CONSUMING` | `activeMembers`, `state`, `observedAt` |

A chave do endpoint normaliza variáveis de path: `/accounts/{id}` e `/accounts/{accountId}` viram
`/accounts/{}`. É isso que liga o `CALLS` de um serviço ao `EXPOSES` do outro, mesmo com nomes de variável
diferentes.

O nome do serviço é o identificador universal. Ele vem do `spring.application.name` e é o mesmo usado como
`group.id` no Kafka, o que permite cruzar o que o código declara com o que o cluster mostra.

## Regras de extração

O extrator usa duas técnicas. ClassGraph lê o bytecode em `target/classes` e cobre tudo que é declarado por
anotação (as constantes já chegam resolvidas pelo compilador). JavaParser lê `src/main/java` e cobre o que é
chamada de método, que não existe no bytecode de forma fácil de interpretar.

| Padrão no código | Vira | Como é lido |
|---|---|---|
| `@RestController` + `@GetMapping` etc. | `EXPOSES` | ClassGraph: path da classe + path do método |
| `restClient.get().uri("/x/{id}")` | `CALLS` | JavaParser: verbo da cadeia, path literal ou constante |
| `webClient.post().uri(...)` | `CALLS` | Igual ao `RestClient` |
| `builder.baseUrl(url)` com `@Value("${...}")` | serviço alvo | JavaParser + resolução no `application.yml` |
| `@KafkaListener(topics = "${...}")` | `CONSUMES` | ClassGraph + resolução de placeholder; tipo do payload pelo parâmetro |
| `kafkaTemplate.send(Topics.X, key, event)` | `PUBLISHES` | JavaParser: tópico por constante, literal ou `@Value`; payload pelo argumento ou pelo genérico do `KafkaTemplate` |
| `@Bean Consumer<T>` / `Function<T,R>` / `Supplier<R>` | `CONSUMES` / `PUBLISHES` | ClassGraph + bindings `<nome>-in-0` / `<nome>-out-0` no yml |
| `streamBridge.send("binding", payload)` | `PUBLISHES` | JavaParser: binding resolvido para `destination` no yml |
| campos do DTO de payload | `Schema` | ClassGraph: campos não estáticos da classe ou record |
| `system-graph.yml` | qualquer relação | Manifesto para o que a análise não pega |

### Como o serviço alvo de uma chamada REST é descoberto

1. Acha o `baseUrl(...)` do client: na atribuição do campo dentro da classe, no inicializador do campo, ou num
   método `@Bean` com o mesmo nome do campo.
2. Resolve o valor. Se for `@Value("${services.account-service.url}")`, busca no `application.yml`.
3. Se o host da URL for um nome (`http://account-service:8080`, `account-service.core.svc`), esse é o serviço.
   Confiança `high`.
4. Se o host for `localhost` ou IP (comum em dev), usa a convenção do nome da propriedade:
   `services.<serviço>.url`, `<serviço>.base-url` e variações. Confiança `medium`.
5. Se nada funcionar, a chamada fica como `unknown`, o extrator avisa, e ela pode ir para o manifesto.

Esse passo 4 é o motivo de valer a pena padronizar o nome das propriedades de URL nos serviços da empresa.

## Ingestão

Cada `service-graph.json` é gravado numa transação. Primeiro apaga todas as relações de saída do serviço
(`EXPOSES`, `CALLS`, `DEPENDS_ON`, `PUBLISHES`, `CONSUMES`) e os `Schema` dele, depois recria a partir do JSON.
Com isso, um `@KafkaListener` removido do código some do grafo no próximo build, sem acumular lixo.

`Endpoint` e `Topic` são compartilhados entre serviços e só são removidos quando ficam sem nenhuma relação. Notas
seguram o nó: um endpoint com nota não some mesmo que o dono pare de expor. Isso é proposital, porque a nota
pode ser justamente "este endpoint foi descontinuado em tal data".

Serviços referenciados mas nunca extraídos (um client chamando um serviço que ainda não tem o job de CI) viram
`Service {indexed: false}`. As tools deixam isso claro para o assistente não tirar conclusão errada.

`OBSERVED_CONSUMING` vem de outra fonte (o `AdminClient` do Kafka) e nunca é apagado pela ingestão do código.
O comando `kafka-runtime` substitui essas relações inteiras a cada execução.

## MCP server

### Tools de domínio e Cypher livre

As perguntas frequentes (impacto, visão geral, consumidores, schema) viraram tools com Cypher fixo. A resposta é
previsível, rápida e já vem no formato que o assistente precisa, inclusive com orientação (`guidance`) sobre o
que é mudança compatível e o que quebra.

`read_cypher` fica para o resto. O assistente chama `graph_model` para ver o schema e escreve a query. É útil
para perguntas que ninguém previu, como "quais serviços consomem mais de dois tópicos".

### Somente leitura

As sessões de leitura abrem com `AccessMode.READ`. O Neo4j rejeita qualquer escrita nesse modo, mesmo em
instância única, com `Writing in read access mode not allowed`. Isso foi testado nesta POC. Também há timeout
de 5 segundos por query e limite de 200 linhas.

Só `record_note` escreve, com uma query fixa que cria a nota. Em produção, o ideal é somar a isso um usuário
Neo4j só de leitura para o MCP. Controle de acesso por papéis (RBAC) só existe no Neo4j Enterprise ou no Aura;
na Community a proteção é o modo de acesso da sessão.

### Transporte

Streamable HTTP em `/mcp`, que é o transporte atual do protocolo. Um servidor central atende todos os devs, sem
nada para instalar na máquina de cada um. As `instructions` do servidor já dizem ao assistente quando usar cada
tool, e o `CLAUDE.md` de cada repositório reforça.

## Segurança

O grafo descreve a arquitetura inteira: nomes de serviços, endpoints internos, tópicos. Em produção:

- MCP server atrás de autenticação (token por dev ou OAuth pelo GitLab), só na rede interna.
- Nada de dados de negócio no grafo. Só estrutura e notas técnicas.
- Notas passam por revisão antes de virar `approved`, para não virar canal de informação errada.
