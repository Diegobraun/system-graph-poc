#!/usr/bin/env bash
set -euo pipefail

JSON='Content-Type: application/json'
ACCOUNT=http://localhost:8081
LOAN=http://localhost:8082
CUSTOMER=http://localhost:8083
PAYMENT=http://localhost:8084
NOTIFICATION=http://localhost:8085
INVESTMENT=http://localhost:8086

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
CUSTOMER_JSON=$(curl -s -X POST "$ACCOUNT/customers" -H "$JSON" -d '{"name":"Ana Souza","document":"123.456.789-00","monthlyIncome":8000}')
echo "$CUSTOMER_JSON"
CUSTOMER_ID=$(echo "$CUSTOMER_JSON" | field id)

echo; echo "2. contatos no customer-service (usados pelo notification-service via @HttpExchange)"
curl -s -X PUT "$CUSTOMER/contacts/$CUSTOMER_ID" -H "$JSON" -d '{"email":"ana@exemplo.com","phone":"+5511999990000","acceptsMarketing":false}'; echo

echo; echo "3. abre conta (account-service publica account-opened via KafkaTemplate)"
ACCOUNT_JSON=$(curl -s -X POST "$ACCOUNT/accounts" -H "$JSON" -d "{\"customerId\":$CUSTOMER_ID}")
echo "$ACCOUNT_JSON"
ACCOUNT_ID=$(echo "$ACCOUNT_JSON" | field id)

echo; echo "4. KYC no customer-service (consome account-opened, chama account, bureau e core-banking, publica customer-kyc-approved)"
wait_for "$CUSTOMER/kyc/$CUSTOMER_ID" "d['status'] == 'APPROVED'"
wait_for "$ACCOUNT/accounts/$ACCOUNT_ID" "d['status'] == 'ACTIVE'"

echo; echo "5. oferta pré-aprovada criada pelo loan-service (esperado 24000, veja o que chega)"
wait_for "$LOAN/offers/$ACCOUNT_ID" "d['kycApproved']"

echo; echo "6. análise de crédito: GraphQL no account-service e perfil de risco no customer-service"
curl -s "$LOAN/credit-analysis/$CUSTOMER_ID"; echo

echo; echo "7. pede empréstimo de 20000 (RestClient + @HttpExchange no account-service, publica loan-disbursed)"
curl -s -X POST "$LOAN/loans" -H "$JSON" -d "{\"accountId\":$ACCOUNT_ID,\"amount\":20000,\"installments\":24}"; echo

echo; echo "8. saldo da conta depois do crédito (account-service consome loan-disbursed)"
wait_for "$ACCOUNT/accounts/$ACCOUNT_ID" "d['balance'] > 0"

echo; echo "9. Pix de 1500: payment-service lê a conta via GraphQL, limite via WebClient, consulta o fraud-service"
curl -s -X POST "$PAYMENT/payments" -H "$JSON" -d "{\"accountId\":$ACCOUNT_ID,\"pixKey\":\"joao@exemplo.com\",\"amount\":1500}"; echo

echo; echo "10. aplica 5000 em CDB no investment-service (publica investment-applied)"
curl -s -X POST "$INVESTMENT/investments" -H "$JSON" -d "{\"accountId\":$ACCOUNT_ID,\"productCode\":\"CDB-LIQ\",\"amount\":5000}"; echo

echo; echo "11. saldo final: 20000 - 1500 - 5000 (account-service consome payment-completed e investment-applied)"
wait_for "$ACCOUNT/accounts/$ACCOUNT_ID" "d['balance'] == 13500"

echo; echo "12. resumo da conta: account-service chama o loan-service via Feign"
curl -s "$ACCOUNT/accounts/$ACCOUNT_ID/summary"; echo

echo; echo "13. notificações enviadas ao cliente (notification-service consome 6 tópicos)"
wait_for "$NOTIFICATION/notifications/$CUSTOMER_ID" "len(d) >= 5"
