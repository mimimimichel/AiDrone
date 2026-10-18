# Bebop IA : une app Android pour discuter avec ton Bebop 2 et le laisser piloter par une IA

Le téléphone devient la télécommande, les yeux et la voix du drone. Il reçoit la vidéo du Bebop 2 (ou Bebop 2 Power), l'envoie à Claude avec ce que tu dis, et Claude répond à voix haute et propose des actions. Ces actions ne sont exécutées qu'après les vérifications de sécurité, et après ton accord si tu le souhaites.

```
 Bebop 2 ──Wi-Fi──► téléphone ──4G──► Claude
   ▲   vidéo + état      │  micro / écran / voix
   └── commandes ◄── garde-fous ◄── action proposée
```

Le téléphone utilise **le Wi‑Fi du drone et la 4G en même temps** : pas besoin de double connexion.

## Obtenir l'APK

### Avec GitHub (rien à installer)
1. Chaque envoi sur la branche `main` lance la compilation : onglet **Actions** du dépôt, workflow « Compiler l'APK ». Tu peux aussi la relancer à la main avec « Run workflow ».
2. Quand c'est vert (environ 5 min), ouvre l'exécution et télécharge l'artefact **BebopIA-apk** (un zip qui contient `app-debug.apk`).
3. Copie l'APK sur ton téléphone, ouvre-le et autorise l'installation depuis cette source.

### Avec Android Studio
Ouvre le dossier du projet, attends la synchronisation Gradle, branche ton téléphone (débogage USB activé) et clique sur ▶ Run.

## Premier lancement

1. **Réglages (⚙)** : colle ta clé API Claude (console.anthropic.com). Le modèle par défaut est `claude-sonnet-5-5`, tu peux le changer.
2. **Teste d'abord en simulation** : bouton « Simulation ». La caméra du téléphone remplace celle du drone et rien ne vole. Parle à l'IA, montre-lui des objets, appuie sur « ▶ IA » et regarde ce qu'elle propose de faire.

## Voler avec le Bebop

1. Allume le Bebop et laisse la **Skycontroller éteinte** : le drone n'accepte qu'un pilote à la fois.
2. Sur le téléphone, connecte le Wi‑Fi au réseau du drone (`Bebop2Power-…` ou `Bebop2-…`). Si Android signale que ce réseau n'a pas d'accès Internet, choisis de **rester connecté**.
3. Vérifie que les **données mobiles sont activées** : c'est par là que passe Claude.
4. Dans l'app : « Connecter le Bebop ». La vidéo apparaît après quelques secondes.
5. L'IA démarre **en pause** : elle te répond, mais ne bouge pas. Appuie sur « ▶ IA » pour la laisser piloter.

### Les commandes

| Commande | Effet |
|---|---|
| 🎤 | Parler au drone (il t'écoute jusqu'à ce que tu te taises) |
| Champ texte + ➤ | Écrire au drone |
| **▶ IA / ⏸ Pause IA** | Laisser l'IA piloter, ou la mettre en pause |
| **Oui / Non** | Accepter ou refuser le déplacement proposé (mode validation) |
| Décoller | Décollage manuel |
| **ATTERRIR** | Atterrissage immédiat, IA en pause |
| Arrêt d'urgence (appui long) | Coupe les moteurs : **le drone tombe**. Dernier recours |

**Mots de sécurité**, traités instantanément sans passer par l'IA : « **stop** », « arrête » ou « pause » pour arrêter l'IA (le drone reste sur place), et « **atterris** » ou « pose-toi » pour atterrir.

Exemples de phrases : « qu'est-ce que tu vois ? », « décolle », « tourne-toi doucement vers la maison », « monte un peu et regarde la gouttière », « reviens au point de départ ».

## Garde-fous

- L'IA ne commande jamais les moteurs : elle propose une action, le code la vérifie puis l'exécute.
- Plafond de 3 m (réglé aussi dans le drone), rayon de 15 m autour du décollage, plus la géobarrière du Bebop.
- 1 m et 90° maximum par action, 40 actions maximum par session.
- Atterrissage automatique sous 25 % de batterie.
- Pas d'image, pas de déplacement. Une réponse illisible de l'IA donne un stationnaire, jamais une action inventée.
- **Mode validation** (activé par défaut) : chaque déplacement attend ton « oui ».
- Si la liaison Wi‑Fi est perdue, l'IA se met en pause. Le Bebop applique alors son propre comportement de sécurité.

Les limites se règlent dans `app/src/main/java/com/bebopia/app/core/Drone.kt` (classe `Limits`).

**Limite importante** : la position est estimée à partir des commandes envoyées, elle dérive avec le vent. Le Bebop n'a pas de capteurs d'obstacles, et une IA peut mal interpréter une image. Vole dans un espace dégagé, à vue, loin des personnes, et respecte la réglementation (catégorie ouverte en France).

## Dépannage

- **« Connexion impossible »** : vérifie que le Wi‑Fi est bien sur le Bebop et que la Skycontroller et FreeFlight sont fermées, puis redémarre le drone.
- **Connecté mais pas de vidéo** : attends 5 à 10 s. Sinon, déconnecte-toi et reconnecte-toi.
- **L'IA ne répond pas** : vérifie la clé API et que les données mobiles sont actives. Le bandeau en bas de la vidéo indique les réseaux disponibles.
- **Le micro ne comprend rien** : la reconnaissance vocale Android utilise souvent Internet. Pour qu'elle marche hors ligne, installe le français dans Réglages > Système > Langues > Saisie vocale.

## Structure du code

- `core/` : du Kotlin pur, testé sur ordinateur.
  - `bebop/` : protocole Parrot ARSDK3 (connexion, commandes, événements) et flux vidéo RTP/H.264.
  - `ai/` : l'agent (boucle, sécurité, validation), le prompt et l'appel à Claude.
  - `Drone.kt` : limites, position estimée, drone simulé.
- `android/` : réseau Wi‑Fi + 4G, voix, décodage vidéo matériel, captures d'image.
- `ui/` : l'écran (Jetpack Compose).
