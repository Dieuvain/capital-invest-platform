# capital-invest-platform

Plateforme numérique de Capital Invest SA (Kolwezi, RDC) : application mobile, carte prépayée
Visa ou Mastercard (physique et virtuelle), menu USSD, back-office et paie B2B.

## Organisation du dépôt

| Dossier | Contenu | Technologie |
| --- | --- | --- |
| `backend/` | Serveur : grand livre, clients, paiements, cartes, crédit, USSD | Kotlin, Spring Boot 3.5, PostgreSQL 16 |
| `mobile/` | Application Android et iOS | Flutter (à venir) |
| `backoffice/` | Back-office et portail employeur | TypeScript, React (à venir) |
| `docs/` | Documents d'analyse et décisions d'architecture | |

## Démarrer le serveur en local

Prérequis : JDK 21 et PostgreSQL 16.

```bash
createuser -P capital            # mot de passe : capital (développement uniquement)
createdb -O capital capital_invest
createdb -O capital capital_invest_test
cd backend
./gradlew test                   # tests sur la base capital_invest_test
./gradlew bootRun                # API sur http://localhost:8080
```

Variables utiles : `DB_URL`, `DB_USER`, `DB_PASSWORD`, `PORT`, `SIMULATORS_ENABLED` (à `false` en production).

## Essayer l'API

```bash
# 1. Ouvrir un client (KYC niveau 1, portefeuille USD créé automatiquement)
curl -s -X POST localhost:8080/v1/customers -H 'Content-Type: application/json' \
  -d '{"phone":"+243970000001","fullName":"Mwamba Ilunga","language":"sw"}'

# 2. Demander une recharge M-Pesa de 50 USD (montants en centimes)
curl -s -X POST localhost:8080/v1/topups -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1c...' -d '{"customerId":"<id>","operator":"MPESA","amountMinor":5000}'

# 3. Simuler la confirmation de l'opérateur
curl -s -X POST localhost:8080/v1/simulators/mobile-money/callbacks -H 'Content-Type: application/json' \
  -d '{"topupId":"<id recharge>","success":true}'

# 4. Consulter le solde
curl -s localhost:8080/v1/accounts/<walletAccountId>/balance
```

## Règles du grand livre

- Tout mouvement d'argent passe par `LedgerService.post` : au moins deux lignes, débits = crédits.
- Le solde n'est jamais stocké : il est calculé à partir des écritures.
- Les écritures sont en ajout seul (un déclencheur PostgreSQL interdit modification et suppression).
- Chaque opération porte une clé d'idempotence : la rejouer ne débite jamais deux fois.
- Un portefeuille client ne passe jamais sous zéro ; la vérification se fait sous verrou.
- Montants en unités mineures (`Long`, centimes) : jamais de `Double` pour de l'argent.

## Documentation

- Dossier d'analyse : https://claude.ai/artifact/GEDWpquzQD3h9fo3LrpHTt
- Analyse UML : https://claude.ai/artifact/KBMn7NR9qxV5wrgXxSNkjj
