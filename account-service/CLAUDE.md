# Integrações com outros serviços

Este serviço faz parte de um sistema maior. O MCP server `system-graph` (configurado em `.mcp.json`) conhece
os endpoints REST, as chamadas entre serviços, os tópicos Kafka e os payloads de todos os serviços.

- Antes de alterar endpoint REST, DTO de resposta, evento Kafka ou classe de payload, chame `impact_of_change`.
- Ao começar a trabalhar aqui, chame `service_overview` com o nome deste serviço.
- Se uma mudança afetar outro serviço, avise antes de seguir e liste os arquivos afetados que a ferramenta retornou.
- Regras de negócio confirmadas que outros times precisam saber vão para `record_note`.
