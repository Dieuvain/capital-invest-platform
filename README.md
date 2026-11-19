# capital-invest-platform

Plateforme numérique de Capital Invest SA (Kolwezi, RDC) : application mobile, carte prépayée
Visa ou Mastercard (physique et virtuelle), menu USSD, back-office et paie B2B.

## Organisation du dépôt

| Dossier | Contenu | Technologie |
| --- | --- | --- |
| `backend/` | Serveur : grand livre, clients, paiements, cartes, crédit, USSD | Kotlin, Spring Boot 3.5, PostgreSQL 16 |
| `mobile/` | Application Android et iOS | Flutter 3.50 (Dart) |
| `backoffice/` | Back-office des agents (KYC 2, promotions, agents, journal) | TypeScript, React |
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

Variables utiles : `DB_URL`, `DB_USER`, `DB_PASSWORD`, `PORT`, `SIMULATORS_ENABLED` (à `false` en production). Pour les opérateurs mobile money, voir plus bas.

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
- `GET /v1/backoffice/me` : l'agent connecté. `GET /v1/backoffice/agents`, `POST /v1/backoffice/agents/{id}/disable` ou `/enable` (administrateurs). Désactiver un agent ferme ses sessions.
- `GET /v1/backoffice/audit` : journal d'audit (conformité et administrateurs).
- Les routes KYC sont réservées aux rôles `KYC_AGENT`, `COMPLIANCE` et `ADMIN`.
- `POST /v1/backoffice/agents` : création d'un agent (`KYC_AGENT`, `COMPLIANCE`, `ADMIN`, `MARKETING`), réservée aux administrateurs. Le premier administrateur est créé au démarrage à partir de `BACKOFFICE_ADMIN_EMAIL` et `BACKOFFICE_ADMIN_PASSWORD`.

Règles :

- Les photos sont chiffrées en AES-256-GCM dans la base. En production, `KYC_ENCRYPTION_KEY` est obligatoire.
- Le contrôle sanctions et PEP est fait à l'envoi de la demande. Une correspondance ne peut être validée que par un agent `COMPLIANCE`. Le contrôle est **simulé** en attendant le choix du fournisseur.
- Un agent n'ouvre que les pièces de la demande qu'il examine. Chaque consultation, validation et refus est inscrit dans `audit_log`, en ajout seul.
- Le contrôle de vivacité du selfie viendra avec le fournisseur d'identité.

## Promotions de l'accueil

Le bandeau défilant de l'app affiche les promotions en cours.

- `GET /v1/promotions` (jeton client) : 5 promotions au plus, triées par priorité. Chacune a un titre, un texte, un style (`OCHRE`, `INK`, `GREEN`, `SKY`) et, si elle a un bouton, son texte et son action dans l'app (`KYC`, `TOPUP`, `TRANSFER`, `LIMITS`).
- Une promotion n'est montrée que si elle est active, dans sa période (`startsAt`, `endsAt`) et destinée au niveau du client (`ALL`, `KYC1` ou `KYC2`). Ainsi « Passez au niveau 2 » disparaît une fois le client vérifié.
- Back-office, rôles `MARKETING` ou `ADMIN` : `GET /v1/backoffice/promotions`, `POST /v1/backoffice/promotions`, `PUT /v1/backoffice/promotions/{id}`. On retire une promotion avec `"active": false` : rien n'est effacé, et chaque changement est inscrit dans `audit_log`.
- Trois promotions de départ sont créées par la migration V7 : passage au niveau 2, recharge mobile money, envoi aux proches.

### Visuels des graphistes

- Une promotion peut porter un visuel conçu par un graphiste : JPEG, PNG ou WebP, **1200 x 520 px** conseillé, 2 Mo au plus. Envoi depuis l'écran de la promotion dans le back-office, ou `POST /v1/backoffice/promotions/{id}/image` (multipart, champ `file`). Le type est vérifié sur le contenu du fichier, pas sur son nom. Chaque envoi est inscrit dans `audit_log`.
- Avec un visuel, seul le titre reste obligatoire (il est lu par le lecteur d'écran) ; le texte et le texte du bouton deviennent facultatifs, et tout le visuel mène à l'action choisie.
- L'app reçoit `imageUrl` (`/v1/promotions/{id}/image?v=…`) et charge l'image avec le jeton du client. Seul un client qui voit la promotion peut lire son visuel. L'adresse change à chaque nouveau visuel, le téléphone peut donc le garder en cache 7 jours.
- Sans visuel, ou si le chargement échoue, l'app dessine la carte de secours aux couleurs de la promotion.

## Recharges mobile money (M-Pesa, Airtel Money, Orange Money)

Chaque opérateur passe par un prestataire, choisi opérateur par opérateur : `PAWAPAY` (agrégateur, un seul contrat pour les trois), `SIMULATOR` en développement, ou plus tard un contrat direct. Passer un opérateur en direct ne demandera qu'un nouveau connecteur (interface `MobileMoneyConnector`) et le changement de sa route.

- **Jamais de crédit sur la foi d'une notification.** Le webhook `POST /v1/webhooks/mobile-money/{prestataire}` ne fait que déclencher la relecture du statut chez le prestataire, par son API authentifiée. Les notifications pawaPay doivent en plus être signées (RFC 9421, clés publiées par pawaPay) : sinon, réponse 401. Tout ce qui arrive est gardé dans `mobile_money_event`.
- **Relances automatiques** : une recharge en attente est relue après 10 s, puis toutes les 20 s, 40 s… jusqu'à 5 minutes. Inconnue du prestataire après 30 minutes : échec (`NOT_RECEIVED`), rien n'a été débité.
- **Recharges à vérifier** (statut `REVIEW`) : montant reçu différent de celui demandé, ou aucune réponse sûre après 24 h. Le client voit « en vérification ». Un agent `COMPLIANCE` ou `ADMIN` crédite (après une nouvelle relecture : on crédite le montant réellement encaissé) ou clôt avec un motif.
- **Rapprochement** chaque nuit à 2 h (heure de Lubumbashi) : chaque recharge de la veille est comparée à son statut chez le prestataire. Les écarts (`CONFIRMED_NOT_CREDITED`, `CREDITED_NOT_CONFIRMED`, `AMOUNT_MISMATCH`, `STILL_PENDING`) sont à traiter dans l'écran « Rapprochement » du back-office. Toute décision est inscrite dans `audit_log`.
- L'argent pawaPay arrive sur le compte de règlement `SETTLEMENT_PAWAPAY_USD`.

Configuration de production :

| Variable | Valeur |
| --- | --- |
| `MOBILE_MONEY_ROUTE_MPESA`, `…_AIRTEL`, `…_ORANGE` | `PAWAPAY` (le serveur refuse de démarrer sur le simulateur quand `SIMULATORS_ENABLED=false`) |
| `PAWAPAY_API_TOKEN` | jeton API du tableau de bord pawaPay (obligatoire dès qu'une route est `PAWAPAY`) |
| `PAWAPAY_BASE_URL` | `https://api.pawapay.io` en production (bac à sable par défaut) |
| `PAWAPAY_CALLBACK_AUTHORITY` | domaine public de l'API, si un proxy change l'en-tête `Host` |

Dans le tableau de bord pawaPay, déclarer l'URL de notification des dépôts : `https://<domaine de l'API>/v1/webhooks/mobile-money/pawapay`, et activer les notifications signées.

Back-office (`COMPLIANCE`, `ADMIN`) : `GET /v1/backoffice/topups/review`, `POST /v1/backoffice/topups/{id}/resolve`, `GET|POST /v1/backoffice/reconciliation/runs`, `GET /v1/backoffice/reconciliation/items`, `POST /v1/backoffice/reconciliation/items/{id}/resolve`.

## Retraits vers mobile money

Le client envoie son solde vers son propre numéro M-Pesa, Airtel Money ou Orange Money, par le même prestataire que les recharges (pawaPay en production).

- `GET /v1/withdrawals/fee?amountMinor=4000` : frais et total avant confirmation. Frais provisoires : 1,5 %, au moins 0,25 $, au plus 7,50 $ (40 $ coûtent 0,60 $).
- `POST /v1/withdrawals` (en-tête `Idempotency-Key`, corps `operator`, `amountMinor`, `pin`) : réponse 202, statut `PENDING`. `GET /v1/withdrawals/{id}` pour suivre.
- **L'argent est bloqué dès la demande** : montant et frais quittent le portefeuille vers le compte `PAYOUTS_IN_TRANSIT_USD`. Impossible de dépenser deux fois la même somme pendant le versement. Les retraits comptent dans le plafond journalier d'envoi.
- Versement confirmé : l'argent passe sur le compte de règlement du prestataire. Refus certain de l'opérateur : montant **et frais** rendus au client, avec le motif.
- **Jamais de remboursement sur un doute.** Sans réponse sûre après 24 h, le retrait passe « à vérifier ». Un agent ne peut rembourser que si le prestataire dit que le versement a échoué ou ne le connaît pas, et ne peut le noter versé que si le prestataire le dit payé. Sinon le client risquerait d'être payé deux fois.
- Le rapprochement de la nuit couvre aussi les retraits : `MARKED_PAID_NOT_PAID` (noté versé, l'opérateur dit échoué : l'agent rembourse) et `PAID_BUT_REFUNDED` (remboursé alors que l'opérateur a payé : à régler à la main).

Côté pawaPay : la même URL de notification sert aux versements (`https://<domaine de l'API>/v1/webhooks/mobile-money/pawapay`, à déclarer aussi pour les « payouts »), et le compte de versement pawaPay doit être approvisionné avant d'ouvrir les retraits.

Back-office (`COMPLIANCE`, `ADMIN`) : les retraits à vérifier apparaissent dans `GET /v1/backoffice/topups/review` (champ `type` : `TOPUP` ou `WITHDRAWAL`), et se traitent par `POST /v1/backoffice/withdrawals/{id}/resolve` (`PAID` ou `REFUND`, avec une note).

## Historique et reçus

Le client voit **une ligne par opération** (pas une ligne par écriture comptable) : recharge, envoi, argent reçu, retrait, retrait remboursé. Chaque ligne donne le montant, les frais et l'effet sur le solde ; la somme des effets donne le solde.

- `GET /v1/me/history` : de la plus récente à la plus ancienne. Filtres `category` (`TOPUP`, `TRANSFER`, `WITHDRAWAL`), `direction` (`IN`, `OUT`), `from` et `to` (dates, heure de Lubumbashi), `limit` (100 au plus). Pagination par `cursor` : la page suivante reste juste même si une opération arrive entre-temps.
- `GET /v1/me/history/{id}` : reçu de l'opération, avec un numéro lisible (`CI-20261119-51C023`) à donner au service client. Une opération qui ne touche pas le portefeuille du client répond 404.
- Ce que voit l'autre client d'un envoi : le nom et le numéro **masqué** (`+243 81• ••• 214`). Le destinataire ne voit pas les frais payés par l'expéditeur.
- Un retrait affiche son état (`PENDING`, `REVIEW`, `FAILED`) ; un retrait refusé apparaît avec, à part, son remboursement frais compris.
- L'app montre l'historique filtrable et le reçu, partageable par WhatsApp ou SMS.

Back-office, rôles `COMPLIANCE` et `ADMIN` (écran « Clients ») : `GET /v1/backoffice/customers?phone=…` (numéro en `+243…`, `243…`, `0…` ou 9 chiffres), `GET /v1/backoffice/customers/{id}/history` et `GET /v1/backoffice/customers/{id}/history/{operation}`. Les numéros y sont complets, et chaque recherche est inscrite dans `audit_log` (`CUSTOMER_VIEWED`).

## Notifications au client

Tout ce qui touche l'argent ou le compte du client est notifié : recharge reçue, argent reçu, envoi effectué, retrait versé, retrait remboursé, correction de solde, décision sur le niveau 2, PIN changé. Un envoi que le client n'a pas fait est souvent le premier signe d'un vol de compte : chaque sortie d'argent est donc notifiée.

- **Rien ne se perd** : la notification est écrite dans la même transaction que le mouvement d'argent (table `notification`), puis le SMS part juste après. Une passerelle indisponible est relancée après 1, 2, 4 et 8 minutes ; après 5 essais le SMS est abandonné, la notification reste dans l'app. Un rejeu d'opération ne renvoie jamais de second SMS.
- **SMS** : 160 caractères au plus, sans accents (un SMS accentué compte double), avec le nouveau solde et le numéro de reçu. Les codes de connexion ne passent pas par là.
- **App** : la cloche de l'accueil porte une pastille tant qu'il reste des notifications non lues. `GET /v1/me/notifications` (avec `unread`) et `POST /v1/me/notifications/read` (toutes, ou `{"ids": [...]}`). Une notification liée à une opération ouvre son reçu.
- La passerelle SMS est encore le simulateur : il faudra brancher un fournisseur (agrégateur SMS ou opérateur) avant la mise en service. Les notifications push viendront plus tard.

## Paiement chez un marchand (QR)

Le client paie en caisse depuis son solde : il touche **Payer**, scanne le QR affiché par le commerce (ou tape le code de 6 caractères écrit dessous), vérifie le nom du commerce, tape le montant et confirme par son PIN. L'app affiche alors une preuve à montrer au caissier (montant, commerce, heure, numéro de reçu).

- **Pas de frais pour le client.** Le marchand reçoit le montant moins sa commission (1 % par défaut, `capital-invest.merchants.default-commission-bps`, **provisoire**, réglable par marchand de 0 à 10 %). La commission va au compte de revenus, comme les frais d'envoi.
- **Le caissier reçoit un SMS** à chaque paiement, sur le numéro du marchand : montant, client (numéro masqué), net après commission, numéro de reçu.
- Le paiement compte dans le plafond journalier de sortie du client, avec les envois et les retraits. Rejouer la demande (même `Idempotency-Key`) ne débite jamais deux fois.
- L'argent reçu reste sur le compte du marchand chez Capital Invest (`MERCHANT_WALLET`, une dette envers lui) jusqu'au reversement, ci-dessous.
- Le QR contient `capitalinvest://pay?m=CODE`. Le code n'a ni `0`/`O` ni `1`/`I`, pour être lu et tapé sans erreur.

API client : `GET /v1/merchants/{code}` (le commerce avant de payer ; un marchand suspendu répond `MERCHANT_NOT_ACTIVE`) et `POST /v1/merchant-payments` (`merchantCode`, `amountMinor`, `pin`, `note` facultative, en-tête `Idempotency-Key`). Dans l'historique, le paiement apparaît en `MERCHANT_PAYMENT` (filtre `category=PAYMENT`).

Back-office, écran « Marchands » : l'administrateur crée le marchand, imprime son affiche QR et peut le suspendre avec un motif (`POST /v1/backoffice/merchants`, `POST /v1/backoffice/merchants/{id}/status`) ; la conformité consulte la liste, le dû à chaque marchand et ses derniers paiements. Création et suspension sont inscrites au journal d'audit.

### Reversement aux marchands

Ce que Capital Invest doit au marchand part vers son **compte mobile money de reversement** (M-Pesa, Airtel Money ou Orange Money), sur demande d'un administrateur depuis l'écran « Marchands » (bouton « Reverser »). Pas de frais : la commission prélevée sur les paiements les couvre.

- C'est un retrait comme celui d'un client, depuis le compte du marchand, avec les **mêmes garanties** : l'argent est bloqué avant l'appel à l'opérateur ; il ne revient au marchand que sur un échec certain ; un doute passe en vérification ; le rapprochement de nuit couvre aussi les reversements. Le même identifiant de demande rejoué ne reverse jamais deux fois.
- Le marchand reçoit un SMS quand le reversement est versé, ou refusé (l'argent reste alors sur son compte).
- **Compte de reversement protégé** : c'est ce qu'un fraudeur chercherait à changer. Le premier compte est utilisable tout de suite ; un changement prévient le marchand par SMS sur son numéro habituel et **bloque les reversements 24 h** (`capital-invest.merchants.payout-account-cooldown-hours`). Chaque changement et chaque reversement est au journal d'audit.
- Reversement minimum : 1,00 $ (`min-payout-minor`).
- API : `POST /v1/backoffice/merchants/{id}/payout-account` (`operator`, `phone`) et `POST /v1/backoffice/merchants/{id}/payouts` (`requestId`, `amountMinor` facultatif : tout ce qui est dû), réservés aux administrateurs. La fiche marchand liste les derniers reversements et leur état.
- **Reversement automatique chaque nuit** : vers 1 h 30 (heure de Lubumbashi, avant le rapprochement de 2 h), tout ce qui est dû à chaque marchand actif part vers son compte de reversement, s'il atteint 10,00 $ (`auto-payout-min-minor`). Un marchand dont le compte vient de changer, ou qui a déjà un reversement en cours ou à vérifier, attend la nuit suivante. La même nuit ne reverse jamais deux fois (identifiant `auto:<date>`), même si la tâche repasse. Le journal d'audit les note comme actions du système, et la fiche marchand les marque « automatique ».
- Activé par défaut pour chaque nouveau marchand ; un administrateur le coupe ou le remet depuis la fiche (`POST /v1/backoffice/merchants/{id}/auto-payout`, `enabled`). Toute la tâche se coupe avec `capital-invest.merchants.auto-payout-enabled: false`, l'heure se règle avec `auto-payout-cron`.
- Pas encore fait : reversement vers un compte bancaire.

## Surveillance des opérations (anti-blanchiment)

Après chaque mouvement d'argent, le serveur regarde le comportement récent des clients concernés. Une règle franchie ouvre une alerte pour la conformité, dans le menu **Surveillance** du back-office. Rien n'est bloqué automatiquement, et le client n'est jamais prévenu : il ne doit pas savoir qu'il est surveillé.

| Règle | Se déclenche quand… (seuils par défaut) |
| --- | --- |
| Rafale d'opérations | 10 sorties d'argent (envois, paiements, retraits) en une heure |
| Montants juste sous le plafond | 3 opérations en 24 h à 90 % ou plus du plafond par opération du client |
| Argent reçu de nombreux clients | 5 expéditeurs différents en 24 h (compte collecteur) |
| Retrait aussitôt l'argent reçu | 80 % ou plus de ce qui est entré en 24 h retiré, à partir de 500,00 $ entrés |

- Une seule alerte ouverte par client et par règle : la suivante attend que la première soit traitée.
- La conformité (ou un administrateur) ouvre les opérations du client, puis **classe sans suite** ou **suspend le compte**, toujours avec une note. Un compte suspendu perd ses sessions, ne peut plus se connecter ni faire bouger son argent ; il se réactive depuis la fiche client, avec une note aussi. Tout passe au journal d'audit.
- Le contrôle tourne après la fin de l'opération, dans sa propre transaction : une erreur de surveillance ne bloque jamais l'argent.
- Seuils PROVISOIRES dans `capital-invest.monitoring` (`application.yml`) : à valider par la conformité, puis à ajuster avec les premiers mois d'activité réelle.
- API : `GET /v1/backoffice/monitoring/alerts` (`status` : `OPEN`, `CLOSED` ou `ALL` ; `customerId` facultatif), `POST /v1/backoffice/monitoring/alerts/{id}/resolve` (`action` : `DISMISS` ou `SUSPEND`, `note`), `POST /v1/backoffice/customers/{id}/status` (`status` : `ACTIVE` ou `SUSPENDED`, `reason`).
- Pas encore fait : déclaration de soupçon à la CENAREF depuis le back-office (aujourd'hui, la note garde la trace de la décision) et règles sur les marchands.

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
