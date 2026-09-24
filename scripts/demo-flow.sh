#!/usr/bin/env bash
set -euo pipefail

JSON='Content-Type: application/json'
ACCOUNT=http://localhost:8081
LOAN=http://localhost:8082

echo "1. cliente com renda de 8000"
CUSTOMER=$(curl -s -X POST "$ACCOUNT/customers" -H "$JSON" -d '{"name":"Ana Souza","document":"123.456.789-00","monthlyIncome":8000}')
echo "$CUSTOMER"
CUSTOMER_ID=$(echo "$CUSTOMER" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

echo; echo "2. abre conta (publica account-opened)"
ACCOUNT_JSON=$(curl -s -X POST "$ACCOUNT/accounts" -H "$JSON" -d "{\"customerId\":$CUSTOMER_ID}")
echo "$ACCOUNT_JSON"
ACCOUNT_ID=$(echo "$ACCOUNT_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
sleep 2

echo; echo "3. oferta pré-aprovada criada pelo loan-service (esperado 24000, veja o que chega)"
curl -s "$LOAN/offers/$ACCOUNT_ID"; echo

echo; echo "4. pede empréstimo de 20000 (loan chama account via REST e publica loan-disbursed)"
curl -s -X POST "$LOAN/loans" -H "$JSON" -d "{\"accountId\":$ACCOUNT_ID,\"amount\":20000,\"installments\":24}"; echo
sleep 2

echo; echo "5. saldo da conta depois do crédito"
curl -s "$ACCOUNT/accounts/$ACCOUNT_ID"; echo
