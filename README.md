<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHEasterBunny

*Événement de Pâques : des lapins à attraper apparaissent dans une zone, avec une limite par jour.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Événement de Pâques : des lapins à attraper apparaissent dans une zone, avec une limite par jour.

Utilisé sur : **Opale**.

## Dépendances

- Obligatoires : `PlaceholderAPI`

## Commandes

| Commande | Description | Permission |
|---|---|---|
| `/sheasterbunny reload` | Relit `config.yml` et `messages.yml` (zone, intervalle, limite du jour, récompenses, textes). Si un fichier est invalide, l'ancienne configuration reste active ; `data.yml` n'est pas touché. | `sheasterbunny.admin.reload` |
| `/sheasterbunny info <joueur>` | Lapins attrapés au total et aujourd'hui, limite et reste du jour. | `sheasterbunny.admin.info` |

Sans argument, la commande affiche les sous-commandes que l'expéditeur a le droit d'utiliser. Elle marche depuis la console.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `sheasterbunny.admin` | Toutes les commandes d'administration. | `op` |
| `sheasterbunny.admin.reload` | `/sheasterbunny reload`. | `op` |
| `sheasterbunny.admin.info` | `/sheasterbunny info <joueur>`. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHEasterBunny/
├── <a href="src/main/resources/config.yml">config.yml</a>     zone, intervalle, limite du jour, récompenses, sons
└── <a href="src/main/resources/messages.yml">messages.yml</a>   textes joueurs et de la commande
</pre>

Si `messages.yml` n'existe pas au démarrage, il est créé à partir de l'ancienne section `messages:` de `config.yml` (textes du serveur conservés) ; cette section n'est plus lue ensuite.

## Plusieurs serveurs de jeu

Les lapins apparaissent dans la zone `spawn-zone` du monde indiqué, et c'est le seul endroit où on peut les attraper. Quand les joueurs sont répartis sur plusieurs serveurs de jeu, ce monde n'est hébergé que par un seul d'entre eux : un joueur qui y entre depuis un autre serveur est envoyé sur celui-là. Le plugin ne doit donc tourner que sur ce serveur :

- `data.yml` (lapins attrapés au total, compteur du jour par joueur) n'a qu'un seul serveur qui l'écrit : la limite `daily-limit.max-per-day` ne se contourne pas en changeant de serveur ;
- sur les autres serveurs, `%sheasterbunny_rabbits%` n'existe pas : le plugin y est absent, il n'y a donc pas de compteur à 0 qui viendrait écraser les vrais totaux dans un classement partagé ;
- rien n'est partagé, il n'y a ni base ni bus à configurer et SHNetwork n'est pas utilisé.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
