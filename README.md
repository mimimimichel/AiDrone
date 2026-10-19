# Bebop IA : une app Android pour discuter avec ton Bebop 2 et le laisser piloter par une IA

Le téléphone devient la télécommande, les yeux et la voix du drone. Il reçoit la vidéo du Bebop 2 (ou Bebop 2 Power) et tu **discutes en direct** avec l'IA (Gemini Live, comme dans Vr2) : elle voit une image par seconde, te répond à voix haute, tu peux lui couper la parole, et elle propose des actions. Ces actions ne sont exécutées qu'après les vérifications de sécurité, et après ton accord si tu le souhaites.

```
 Bebop 2 ──Wi-Fi──► téléphone ──4G──► Gemini
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

1. **Réglages (⚙)** : colle ta **clé Gemini**, gratuite sur [aistudio.google.com/apikey](https://aistudio.google.com/apikey) (la même que pour Vr2). Une clé Claude (payante) reste possible en option, sans conversation en direct.
2. **Teste d'abord en simulation** : bouton « Simulation ». La caméra du téléphone remplace celle du drone et rien ne vole. Parle à l'IA, montre-lui des objets, appuie sur « ▶ IA » et regarde ce qu'elle propose de faire.

## Voler avec le Bebop

1. Allume le Bebop et laisse la **Skycontroller éteinte** : le drone n'accepte qu'un pilote à la fois.
2. Sur le téléphone, connecte le Wi‑Fi au réseau du drone (`Bebop2Power-…` ou `Bebop2-…`). Si Android signale que ce réseau n'a pas d'accès Internet, choisis de **rester connecté**.
3. Vérifie que les **données mobiles sont activées** : c'est par là que passe l'IA.
4. Dans l'app : « Connecter le Bebop ». La vidéo apparaît après quelques secondes.
5. L'IA démarre **en pause** : elle te répond, mais ne bouge pas. Appuie sur « ▶ IA » pour la laisser piloter.

### Les commandes

| Commande | Effet |
|---|---|
| 🎤 | Ouvrir la conversation : parle normalement, le drone répond, tu peux lui couper la parole. Réappuie pour la fermer |
| Champ texte + ➤ | Écrire au drone |
| **▶ IA / ⏸ Pause IA** | Laisser l'IA piloter, ou la mettre en pause |
| **Oui / Non** | Accepter ou refuser le déplacement proposé (mode validation) |
| Décoller | Décollage manuel |
| **ATTERRIR** | Atterrissage immédiat, IA en pause |
| Arrêt d'urgence (appui long) | Coupe les moteurs : **le drone tombe**. Dernier recours |

**Mots de sécurité**, traités instantanément sans passer par l'IA : « **stop** », « arrête » ou « pause » pour arrêter l'IA (le drone reste sur place), et « **atterris** » ou « pose-toi » pour atterrir.

Exemples de phrases : « qu'est-ce que tu vois ? », « décolle », « tourne-toi doucement vers la maison », « monte un peu et regarde la gouttière », « reviens au point de départ ».

**Mission de recherche sur un terrain** : pose le drone au milieu d'un terrain dégagé et dis « trouve une voiture rouge » ou « cherche le chien et approche-toi ». L'IA lance une mission autonome dont le plan s'affiche et se coche tout seul :
1. décoller et monter à l'altitude de recherche (8 m par défaut, jamais au-dessus du plafond réglé) ;
2. tour d'horizon depuis le point de départ ;
3. spirale qui s'élargit autour du départ (un point tous les 4 à 15 m selon l'altitude), avec un tour complet à chaque point ; chaque image est analysée par Gemini, et une « piste » incertaine est vérifiée de plus près ;
4. cible trouvée : le drone se met face à elle, estime sa distance et s'en approche en gardant 4 m de marge si tu l'as demandé ;
5. rien trouvé, zone couverte ou batterie trop basse pour rentrer (30 % + 1 % tous les 25 m d'éloignement) : retour au point de départ (estimé, sans GPS).

Avant de voler dehors, règle dans ⚙ le **plafond** (2 à 120 m, la limite légale) et le **rayon** (5 à 500 m) : ils sont envoyés au Bebop. Il faut au moins 45 % de batterie pour lancer une mission. « Stop » ou « Arrêter le plan » arrête tout, le drone reste sur place. Le détecteur d'obstacles ne voit que devant : vole loin des arbres, des lignes électriques et des gens, et garde toujours le drone en vue.

**Chercher un objet** : « trouve la tulipe rouge », « cherche le robot aspirateur et approche-toi ». Le drone tourne sur lui-même par pas de 45° ; à chaque arrêt, une image nette est analysée par Gemini Robotics-ER (spécialisé dans le repérage d'objets). S'il ne trouve rien à hauteur, il refait un tour caméra inclinée vers le bas. Une fois l'objet trouvé, il se met face à lui et, si tu l'as demandé, s'en approche par petits pas (garde-fous et détecteur d'obstacles actifs). L'avancement s'affiche dans la conversation (🔎). En simulation, l'app te dit de tourner le téléphone à chaque pas.

**Objectifs et plans** (avec Gemini) : donne un objectif à voix haute (« va voir ce qu'il y a derrière le carton », « trouve le robot aspirateur »). L'IA regarde, propose un plan en quelques étapes qui s'affiche à l'écran ; dis « oui » ou touche « Lancer le plan ». Elle exécute alors les étapes une par une, vérifie chacune sur l'image, et propose un nouveau plan si ce qu'elle voit ne colle pas. Pendant un plan validé, les déplacements ne redemandent pas ton accord (les garde-fous et le détecteur d'obstacles restent actifs) ; « stop » ou « Arrêter le plan » l'interrompt. Avant chaque déplacement, l'IA écrit ce qu'elle voit (ligne « 👁 »).

**Voix de robot** (activée par défaut, réglable dans ⚙) : la voix de l'IA passe par un effet métallique (modulation en anneau et résonance, façon robot de film), et l'IA adopte un ton un peu mécanique.

## Voir ce que voit le drone dans un casque (Meta Quest)

Dans ⚙, active **Vue dans le casque**. L'app affiche une adresse du type `http://192.168.1.23:8080` : ouvre-la dans le navigateur du Quest (ou de n'importe quel écran). Tu vois la vidéo du drone en grand, avec la batterie, l'altitude, ce que dit l'IA et les alertes d'obstacle. Le téléphone et le casque doivent être sur le même réseau :
- **Mavic Mini** (relié par câble) : le Wi-Fi de la maison, ou le partage de connexion du téléphone ;
- **Bebop** : connecte aussi le casque au Wi-Fi du Bebop.
- **Dehors, sans box** : active le **point d'accès mobile** du téléphone et connecte le Quest dessus (avec le Mavic relié par câble, la 4G continue de servir l'IA).

**La caméra suit ta tête** : ouvre l'adresse en `https://…:8443` (le casque affiche un avertissement de sécurité la première fois, car le certificat est fabriqué par l'app : Avancé → Continuer), puis touche **🥽 Entrer en VR**. La vidéo s'affiche devant tes yeux et :
- lever ou baisser la tête oriente la nacelle en 1 pour 1, dans ses limites (Mavic : de 0° à -90° ; Bebop : caméra numérique, avec aussi gauche/droite) ;
- tourner la tête fait tourner le Mavic du même angle (90° de tête = 90° de drone, d'après sa boussole), dès l'entrée en VR. La nacelle du Mini ne pivote pas à gauche ou à droite : c'est le drone qui tourne ;
- **grip** (gâchette arrière) : coupe ou remet la rotation du drone ;
- **gâchette avant** : recentrer (la direction actuelle devient « devant »).
La rotation par la tête s'arrête dès que l'IA déplace le drone, ou si le casque ne répond plus pendant une seconde.

**Cockpit** : la page du casque est un cockpit d'avion. Au centre, la vidéo avec un affichage tête haute : horizon artificiel et indicateur de roulis, cap (boussole) avec le point de départ (H), échelles de vitesse et d'altitude (plafond en rouge), vitesse verticale, angle de la nacelle, alerte d'obstacle, proposition de l'IA à valider et sous-titres. À gauche : batterie, temps de vol, retour au départ (flèche et distance), GPS, mode de vol, capteurs. À droite : état de l'IA, plan en cours étape par étape, conversation. En haut : heure et alertes ; en bas : commandes du casque.

**Parler à l'IA depuis le casque** : sur la page `https`, touche **🎙 Parler à l'IA ici** (autorise le micro la première fois), ou entre en VR : l'audio démarre aussi. Le micro du Quest remplace celui du téléphone et la voix de l'IA sort dans le casque (le téléphone se tait). La conversation s'ouvre toute seule si elle ne l'était pas. Tu peux toujours couper la parole à l'IA, et « stop » / « atterris » restent instantanés.

## Garde-fous

- L'IA ne commande jamais les moteurs : elle propose une action, le code la vérifie puis l'exécute.
- Plafond de 3 m (réglé aussi dans le drone), rayon de 15 m autour du décollage, plus la géobarrière du Bebop.
- Distance max par action **réglable** dans ⚙ (de 0,2 à 1 m), 90° maximum, 40 actions maximum par session.
- **Détecteur d'obstacles** (activé par défaut) : quand le drone avance, le téléphone analyse la vidéo une douzaine de fois par seconde. Si quelque chose grossit trop vite au centre de l'image (contact dans moins d'environ 2 s), le déplacement est annulé tout de suite, sans l'IA, et l'avance est bloquée 5 s. Il ne voit pas sur les côtés ni au-dessus, rate les vitres et les murs blancs unis, et se met en veille quand la caméra regarde vers le sol. En simulation, tu peux le tester en approchant le téléphone d'un mur pendant un déplacement vers l'avant.
- Atterrissage automatique sous 25 % de batterie.
- Pas d'image, pas de déplacement. Une réponse illisible de l'IA donne un stationnaire, jamais une action inventée.
- **Mode validation** (activé par défaut) : chaque déplacement attend ton « oui » (dit à voix haute ou bouton Oui). C'est l'application qui l'entend, pas l'IA.
- Si le drone s'interrompt tout seul (il s'entend dans le haut-parleur), désactive « Je peux couper la parole au drone » dans les réglages, ou mets des écouteurs.
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
