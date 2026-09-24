# Templates de GitLab CI

| Arquivo | Uso |
|---|---|
| `system-graph.gitlab-ci.yml` | Template com os jobs `.system-graph` (extração e ingestão no merge da branch default) e `.system-graph-runtime` (leitura dos consumer groups em pipeline agendado) |
| `example-service.gitlab-ci.yml` | Como um serviço inclui o template |

O template espera o `graph-extractor.jar` publicado no Package Registry de um projeto da plataforma
(`SYSTEM_GRAPH_PROJECT_ID`) e as variáveis `NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD` configuradas no grupo.
Para `.system-graph-runtime`, também `KAFKA_BOOTSTRAP`.

Os repositórios [system-graph-account-service](https://github.com/Diegobraun/system-graph-account-service) e
[system-graph-loan-service](https://github.com/Diegobraun/system-graph-loan-service) têm um `.gitlab-ci.yml` que
inclui este template, do jeito que um serviço da empresa faria. Como estão no GitHub, o que roda de fato neles é o
equivalente em GitHub Actions (`.github/workflows/system-graph.yml`), que baixa o extrator da release desta
plataforma. O passo a passo completo está em [docs/rollout-gitlab.md](../docs/rollout-gitlab.md).
