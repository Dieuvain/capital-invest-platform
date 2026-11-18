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

Toutes les routes `/v1` demandent `Authorization: Bearer <jeton>`, sauf l'inscription, la connexion
et les simulateurs. Les montants sont en centimes.

```bash
# 1. Recevoir le code SMS (simulé), puis le lire
curl -s -X POST localhost:8080/v1/auth/otp -H 'Content-Type: application/json' -d '{"phone":"+243970000001"}'
curl -s localhost:8080/v1/simulators/sms/+243970000001

# 2. S'inscrire avec le code et un PIN (4 chiffres, ni 1234 ni 1111)
curl -s -X POST localhost:8080/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"phone":"+243970000001","code":"123456","fullName":"Mwamba Ilunga","language":"sw","pin":"2580","deviceId":"tel-1"}'
# -> {"accessToken":"...", ...}

# 3. Recharger 50 USD par M-Pesa, puis simuler la confirmation de l'opérateur
curl -s -X POST localhost:8080/v1/topups -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: 6f1c' \
  -H 'Content-Type: application/json' -d '{"operator":"MPESA","amountMinor":5000}'
curl -s -X POST localhost:8080/v1/simulators/mobile-money/callbacks -H 'Content-Type: application/json' \
  -d '{"topupId":"<id>","success":true}'

# 4. Envoyer 12,50 USD à un autre client (PIN obligatoire), puis lire son solde
curl -s -X POST localhost:8080/v1/transfers -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: 9a2b' \
  -H 'Content-Type: application/json' -d '{"toPhone":"+243970000002","amountMinor":1250,"pin":"2580"}'
curl -s localhost:8080/v1/me/balance -H "Authorization: Bearer $TOKEN"

# 4 bis. Connaître les frais avant de confirmer
curl -s 'localhost:8080/v1/transfers/fee?amountMinor=1250' -H "Authorization: Bearer $TOKEN"

# 5. Voir ses plafonds et ce qui reste à envoyer aujourd'hui
curl -s localhost:8080/v1/me/limits -H "Authorization: Bearer $TOKEN"

# 6. PIN oublié : code SMS, puis nouveau PIN
curl -s -X POST localhost:8080/v1/auth/pin-reset/otp -H 'Content-Type: application/json' -d '{"phone":"+243970000001"}'
curl -s -X POST localhost:8080/v1/auth/pin-reset -H 'Content-Type: application/json' \
  -d '{"phone":"+243970000001","code":"123456","newPin":"7391","deviceId":"tel-neuf"}'
```

## Sécurité de la connexion

- Code SMS à 6 chiffres, valable 5 minutes, 5 essais au plus, 3 demandes au plus par 10 minutes ; stocké haché (HMAC).
- PIN haché en BCrypt ; 5 erreurs bloquent le compte 15 minutes ; le PIN est redemandé pour chaque envoi.
- Jetons de session aléatoires, stockés hachés, révocables ; expiration après 15 minutes d'inactivité et 12 heures au plus.
- PIN oublié : code SMS, nouveau PIN, toutes les anciennes sessions fermées. Les envois sont ensuite suspendus 24 heures, contre le vol de carte SIM. La demande de code répond de la même façon que le numéro ait un compte ou non.
- En production : `OTP_SECRET` et `KYC_ENCRYPTION_KEY` obligatoires, `SIMULATORS_ENABLED=false`.

## Plafonds KYC

Valeurs **provisoires**, à valider par la conformité (fichier `application.yml`, section `capital-invest.limits`).

| Niveau | Par opération | Envoyé par jour | Solde maximal |
|---|---|---|---|
| KYC 1 | 200 $ | 600 $ | 1 000 $ |
| KYC 2 | 4 000 $ | 10 000 $ | 20 000 $ |

- La journée se compte à l'heure de Lubumbashi.
- Le solde maximal compte aussi les recharges en attente, et s'applique au destinataire d'un envoi sans révéler son solde.
- Les contrôles se font sous verrou du portefeuille : deux opérations simultanées ne peuvent pas dépasser un plafond ensemble.

## Frais d'envoi

Valeurs **provisoires**, à fixer par la direction générale (`capital-invest.fees` dans `application.yml`) : 1 % du montant, au moins 0,10 $, au plus 5 $.

- L'expéditeur paie le montant plus les frais ; le destinataire reçoit le montant entier.
- Les frais sont crédités au compte `FEES_REVENUE_USD` dans la même transaction du grand livre.
- Les frais ne comptent pas dans le plafond journalier d'envoi.

## Passage au KYC 2

Côté client (jeton client) :

1. `POST /v1/me/kyc/documents/{ID_FRONT|ID_BACK|SELFIE}` : photo JPEG ou PNG, champ multipart `file`, 5 Mo au plus. Le type est vérifié sur le contenu du fichier.
2. `POST /v1/me/kyc/submission` : `{"idType":"VOTER_CARD|PASSPORT","idNumber":"…","birthDate":"1990-05-12"}`. Le verso est obligatoire pour la carte d'électeur. Il faut avoir 18 ans.
3. `GET /v1/me/kyc` : niveau actuel et état de la dernière demande, avec le motif en cas de refus.

Côté back-office (jeton agent, distinct des jetons clients) :

- `POST /v1/backoffice/auth/login` : e-mail et mot de passe (12 caractères au moins). 5 erreurs bloquent le compte 15 minutes ; session de 15 minutes d'inactivité, 8 heures au plus.
- `GET /v1/backoffice/kyc?status=PENDING`, `GET /v1/backoffice/kyc/{id}`, `GET /v1/backoffice/kyc/{id}/documents/{docId}`.
- `POST /v1/backoffice/kyc/{id}/approve` ou `/reject` avec `{"reason":"…"}`. Le client reçoit un SMS.
- `POST /v1/backoffice/agents` : création d'un agent (`KYC_AGENT`, `COMPLIANCE`, `ADMIN`), réservée aux administrateurs. Le premier administrateur est créé au démarrage à partir de `BACKOFFICE_ADMIN_EMAIL` et `BACKOFFICE_ADMIN_PASSWORD`.

Règles :

- Les photos sont chiffrées en AES-256-GCM dans la base. En production, `KYC_ENCRYPTION_KEY` est obligatoire.
- Le contrôle sanctions et PEP est fait à l'envoi de la demande. Une correspondance ne peut être validée que par un agent `COMPLIANCE`. Le contrôle est **simulé** en attendant le choix du fournisseur.
- Un agent n'ouvre que les pièces de la demande qu'il examine. Chaque consultation, validation et refus est inscrit dans `audit_log`, en ajout seul.
- Le contrôle de vivacité du selfie viendra avec le fournisseur d'identité.

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
