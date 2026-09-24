#!/usr/bin/env bash
set -euo pipefail

JSON='Content-Type: application/json'
ACCOUNT=http://localhost:8081
LOAN=http://localhost:8082

wait_for() {
  local url=$1 check=$2
  for _ in $(seq 1 60); do
    body=$(curl -s -w '\n%{http_code}' "$url")
    if [ "$(echo "$body" | tail -1)" = "200" ] && echo "$body" | head -1 | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if ($check) else 1)"; then
      echo "$body" | head -1
      return
    fi
    sleep 1
  done
  echo "timeout waiting for $url" >&2
  return 1
}

field() {
  python3 -c "import json,sys; print(json.load(sys.stdin)[\"$1\"])"
}

echo "1. cliente com renda de 8000"
CUSTOMER=$(curl -s -X POST "$ACCOUNT/customers" -H "$JSON" -d '{"name":"Ana Souza","document":"123.456.789-00","monthlyIncome":8000}')
echo "$CUSTOMER"
CUSTOMER_ID=$(echo "$CUSTOMER" | field id)

echo; echo "2. abre conta (account-service publica account-opened via KafkaTemplate)"
ACCOUNT_JSON=$(curl -s -X POST "$ACCOUNT/accounts" -H "$JSON" -d "{\"customerId\":$CUSTOMER_ID}")
echo "$ACCOUNT_JSON"
ACCOUNT_ID=$(echo "$ACCOUNT_JSON" | field id)

echo; echo "3. oferta pré-aprovada criada pelo loan-service (esperado 24000, veja o que chega)"
wait_for "$LOAN/offers/$ACCOUNT_ID" "True"

echo; echo "4. análise de crédito: loan-service consulta o account-service via GraphQL"
curl -s "$LOAN/credit-analysis/$CUSTOMER_ID"; echo

echo; echo "5. pede empréstimo de 20000 (RestClient + @HttpExchange no account-service, publica loan-disbursed)"
curl -s -X POST "$LOAN/loans" -H "$JSON" -d "{\"accountId\":$ACCOUNT_ID,\"amount\":20000,\"installments\":24}"; echo

echo; echo "6. saldo da conta depois do crédito (account-service consome loan-disbursed)"
wait_for "$ACCOUNT/accounts/$ACCOUNT_ID" "d['balance'] > 0"

echo; echo "7. resumo da conta: account-service chama o loan-service via Feign"
curl -s "$ACCOUNT/accounts/$ACCOUNT_ID/summary"; echo
