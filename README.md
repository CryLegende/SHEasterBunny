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

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHEasterBunny/
└── <a href="src/main/resources/config.yml">config.yml</a>
</pre>

## Plusieurs serveurs de jeu

Les lapins apparaissent dans la zone `spawn-zone` du monde `Easter`, et c'est le seul endroit où on peut les attraper. Quand les joueurs sont répartis sur plusieurs Opale, ce monde n'est hébergé que par un seul d'entre eux (celui du spawn et des événements) : un joueur qui y entre depuis un autre Opale est envoyé sur celui-là. Le plugin ne doit donc tourner que sur cet Opale :

- `data.yml` (lapins attrapés au total, compteur du jour par joueur) n'a qu'un seul serveur qui l'écrit : la limite `daily-limit.max-per-day` ne se contourne pas en changeant d'Opale ;
- sur les autres Opale, `%sheasterbunny_rabbits%` n'existe pas : le plugin y est absent, il n'y a donc pas de compteur à 0 qui viendrait écraser les vrais totaux dans un classement partagé ;
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
