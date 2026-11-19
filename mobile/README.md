# Application mobile Capital Invest (Flutter)

Parcours client branchés sur l'API `backend/` :

- ouverture de compte par code SMS, PIN, connexion, PIN oublié ;
- accueil : solde, niveau KYC, reste à envoyer aujourd'hui, derniers mouvements ;
- recharge M-Pesa, Airtel Money ou Orange Money, avec attente de la validation sur le téléphone ;
- envoi à un autre client, avec les frais affichés avant la confirmation par PIN ;
- passage au niveau 2 : type et numéro de pièce, date de naissance, photos recto, verso et selfie.

Charte : encre `#231F20`, ocre `#FBB03F`, police Hanken Grotesk (SIL OFL, dans `assets/fonts`).

Captures d'écran : [`docs/captures-app`](../docs/captures-app).

## Lancer l'app

```bash
cd mobile
flutter pub get
# Serveur local vu depuis l'émulateur Android (valeur par défaut) :
flutter run
# Autre serveur :
flutter run --dart-define=API_URL=https://api.exemple.cd
```

Avec les simulateurs du serveur, le code SMS se lit sur `GET /v1/simulators/sms/{numéro}` et une recharge se confirme
avec `POST /v1/simulators/mobile-money/callbacks`.

## Tests

```bash
flutter analyze
flutter test                                   # client API, formats, parcours d'écrans (faux serveur)
flutter test integration_check/real_server_test.dart   # contre le vrai serveur sur localhost:8080
flutter test integration_check/screenshots_test.dart --update-goldens   # régénère les captures
```

## Choix de sécurité

- Le jeton de session reste en mémoire seulement : à chaque ouverture de l'app, le client entre son PIN.
- Chaque recharge et chaque envoi portent une clé d'idempotence : un nouvel essai après une coupure réseau ne paie jamais deux fois.
- Une session expirée (15 minutes d'inactivité) ramène à l'écran PIN.

## Reste à faire

- Traductions kiswahili et lingala (l'app est en français pour l'instant).
- Libellés précis dans l'historique (recharge, envoi, frais) : le serveur doit d'abord exposer le type d'opération.
- Carte virtuelle, notifications, mode hors ligne.
