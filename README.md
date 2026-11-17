# MySeedbox

Appli Android pour suivre et piloter sa seedbox (seedbox.fr) : **qBittorrent**, **Radarr**, **Sonarr** et **Prowlarr**. Les demandes de films et séries restent dans Jellyseerr ; cette appli s'occupe de ce qui se passe ensuite.

## Fonctionnalités

- **Torrents** (qBittorrent) : vitesses globales, liste des torrents avec progression, état, ratio et temps restant, filtres (en cours, terminés, erreurs), pause/reprise et suppression (avec ou sans les fichiers). Actualisation toutes les 5 s.
- **Imports** : files d'attente de Radarr et Sonarr réunies (progression, état de l'import, messages d'erreur ou d'avertissement).
- **Récents** : derniers films (Radarr) et dernières séries (Sonarr) importés, en grille avec pochette ; une carte par série avec les épisodes arrivés, et la date d'import.
- **Calendrier** : épisodes et films attendus sur 30 jours, groupés par jour, avec une coche quand le fichier est déjà là.
- **État** : version et santé de chaque service, espace disque (Radarr/Sonarr), espace libre et ratio global (qBittorrent), indexeurs actifs ou en échec (Prowlarr).
- **Réglages** : adresse, clé API (ou identifiants qBittorrent) et bouton « Tester » pour chaque service. Les secrets sont chiffrés avec une clé du Keystore Android et ne sont jamais dans le code.

- **Sauvegarde** (Réglages) : export de toute la configuration dans un fichier chiffré par un mot de passe (AES-256-GCM, clé dérivée par PBKDF2), à enregistrer sur Google Drive ou ailleurs, puis import sur un autre téléphone. Le mot de passe n'est enregistré nulle part : sans lui, la sauvegarde est inutilisable.

Tirer vers le bas pour actualiser un écran.

## Configuration

Pour chaque service, l'adresse complète avec le port, par exemple `https://cluster1k.seedbox.fr:45338` (les mêmes valeurs que dans Jellyseerr). Si le service a une URL de base, l'ajouter à la fin : `https://cluster1k.seedbox.fr:45338/radarr`.

- Radarr, Sonarr, Prowlarr : clé API dans Paramètres › Général › Sécurité.
- qBittorrent : utilisateur et mot de passe de la WebUI.

## Installation

APK de test toujours à jour : [MySeedbox-debug.apk](https://github.com/florianfish/MySeedbox/releases/download/debug/MySeedbox-debug.apk). Les versions publiées sont dans les **Releases**. Android 8.0 minimum.

## Build

CI fournie par [Forge-Apk](https://github.com/florianfish/Forge-Apk) : APK de debug à chaque push et PR, release signée sur un tag `v*` ou via le bouton « Run workflow » (secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

En local : Gradle 9.9.1, JDK 17, SDK Android 37, puis `gradle assembleDebug`.
