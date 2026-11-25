# Back-office des agents (React, TypeScript)

Écran web du personnel de Capital Invest, branché sur `/v1/backoffice` du serveur.

| Menu | Rôles | Ce qu'on y fait |
|---|---|---|
| Demandes KYC 2 | Agent KYC, Conformité, Administrateur | File d'attente, photos, contrôle sanctions, validation ou refus motivé |
| Promotions | Marketing, Administrateur | Bandeau de l'accueil de l'app, avec aperçu, dates et public |
| Crédits | Agent de crédit, Administrateur | Crédit express : demandes au-dessus du seuil de revue (score et raisons), crédits en retard ou en défaut, rééchelonnement ; passage en perte et réglages pour l'administrateur |
| Agents | Administrateur | Création des comptes, désactivation (ferme aussitôt les sessions) |
| Journal d'audit | Conformité, Administrateur | Qui a fait quoi, et quand |

Le serveur vérifie les mêmes droits : cacher un menu n'est qu'un confort.

## Lancer en local

```bash
# 1. Le serveur, avec un premier administrateur
cd backend
BACKOFFICE_ADMIN_EMAIL=admin@capitalinvest.cd BACKOFFICE_ADMIN_PASSWORD='un-mot-de-passe-long' ./gradlew bootRun

# 2. L'écran (Node 22)
cd backoffice
npm ci
npm run dev        # http://localhost:5173, relaie /v1 vers http://localhost:8080
```

`API_TARGET` change le serveur visé en local.

## Mettre en ligne

`npm run build` produit un site statique dans `dist/`.

- **Même domaine que l'API** (recommandé) : servir `dist/` et relayer `/v1/` vers le serveur.
- **Domaine à part** : construire avec `VITE_API_URL=https://api.exemple.cd npm run build` et déclarer ce domaine côté serveur dans `BACKOFFICE_ALLOWED_ORIGINS`.

## Sécurité

- Le jeton agent reste en `sessionStorage` : il disparaît à la fermeture de l'onglet. Le serveur ferme la session après 15 minutes sans activité, 8 heures au plus.
- Les photos d'identité sont chargées en mémoire, jamais mises en cache (`Cache-Control: no-store`). Chaque consultation est inscrite au journal.

## Tests

```bash
npm run typecheck
npm test           # 21 tests, contre un faux serveur
```

Les captures de `docs/captures-backoffice` ont été prises contre le vrai serveur avec des données de démonstration (pièces marquées « SPÉCIMEN »).
