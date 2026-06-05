# SphereLib (CocoNationLib)

Bibliothèque commune pour les plugins Minecraft serveur CocoWorld.

## Vue d'ensemble

SphereLib est une bibliothèque Java fournissant des utilitaires, des classes de positionnement, un système de commandes et une gestion de configuration pour les plugins Minecraft basés sur PaperMC/Folia.

## Ce que fait la bibliothèque

- **Positionnement 2D/3D** : Classes `Vector2D`, `Vector3D`, `Zone2D`, `Zone3D` pour gérer positions et zones
- **Système de commandes** : Framework avec `SubCommand`, `CommandManager` et auto-complétion
- **Utilitaires** : Chat cliquable, sons, têtes joueurs, particules, progression, NBT custom
- **Configuration** : Gestion intelligente avec merge de configs et blacklist
- **Internationalisation** : Système de traduction avec placeholders
- **Compatibilité** : Support PaperMC 1.19+ et Folia

## Ce que ne fait pas la bibliothèque

- Ne fournit pas de mécaniques de jeu (économie, teleport, etc.)
- Ne gère pas de base de données
- Ne fournit pas d'interface GUI
- N'est pas un plugin autonome (library uniquement)

## Stack technique

- **Java** : 17+ (compilé avec Java 21)
- **API** : PaperMC 1.19+
- **Compatible Folia** : Oui
- **Build** : Gradle 8.3+ avec shadow plugin
- **Dépendances** : Paper API (compileOnly)

## Structure du dépôt

```
SphereLib/
├── src/main/java/org/leralix/lib/
│   ├── SphereLib.java           # Classe principale
│   ├── commands/                # Système de commandes
│   ├── data/                    # Données (version, sons)
│   ├── lang/                    # Internationalisation
│   ├── position/                # Vector2D/3D, Zone2D/3D
│   └── utils/                   # Utilitaires divers
├── src/main/resources/
│   ├── config.yml               # Configuration principale
│   ├── plugin.yml               # Manifest plugin
│   └── lang/                    # Fichiers de traduction
└── build.gradle                 # Configuration build
```

## Installation / Build

```bash
# Cloner le dépôt
git clone https://github.com/Leralix/SphereLib.git

# Builder
./gradlew build

# Output : build/libs/CocoNationLib-1.0-all.jar
```

**En tant que dépendance** (dans `build.gradle`) :

```gradle
dependencies {
    compileOnly 'io.github.leralix:SphereLib:1.0'
}
```

## Configuration

### config.yml

```yaml
enableSounds: true
sounds:
  LEVEL_UP: ["ENTITY_PLAYER_LEVELUP", "1", "1"]
  ADD: ["BLOCK_NOTE_BLOCK_HAT", "1", "8"]
  REMOVE: ["BLOCK_NOTE_BLOCK_HAT", "1", "6"]
  # ... voir fichier complet
```

### lang.yml

```yaml
language: en
```

Les traductions se trouvent dans `src/main/resources/lang/{tag}/main.yml`.

## API principales

### Position

```java
// 2D
Vector2D pos = new Vector2D(x, z, worldID);
pos.getDistance(other);
pos.getArea();

// 3D
Vector3D pos3d = new Vector3D(location);
pos3d.getLocation(world);

// Orientation
Vector3DWithOrientation oriented = new Vector3DWithOrientation(x, y, z, yaw, pitch);

// Zones
Zone2D zone = new Zone2D(min, max);
Zone3D zone3d = new Zone3D(min, max);
```

### Commandes

```java
public class MyCommand extends SubCommand {
    public String getName() { return "mycommand"; }
    public String getDescription() { return "Description"; }
    public void perform(Player player, String[] args) { /* ... */ }
}

CommandManager.register(new MyCommand());
```

### Utilitaires

```java
// Chat cliquable
ChatUtils.sendClickableCommand(player, "message", "/command");

// Sons
SoundUtil.playSound(player, SoundEnum.ADD);

// Têtes joueurs
ItemStack head = HeadUtils.getPlayerHead(player);

// Particules
ParticleUtils.drawBox(player, location, size, particle);

// Progression
String bar = ProgressBar.createProgressBar(current, max, length, color);

// Config
ConfigUtil.saveAndUpdateResource(plugin, "config.yml");
ConfigUtil.addCustomConfig(plugin, "custom.yml", ConfigTag.MAIN);
```

## Événements, commandes, permissions

### Commandes
Aucune commande publique n'est exposée (le framework est à usage interne).

### Permissions
Aucune permission par défaut (géré via `CommandManager`).

### Événements
Aucun événement Bukkit public n'est enregistré par cette librairie.

## Notes de développement

- La librairie utilise un système de tags pour gérer plusieurs fichiers de config
- Les classes `Vector2D`/`Vector3D` sont optimisées pour éviter les allocations `Location` inutiles
- Le système de sons est préconfiguré dans `config.yml` pour simplifier l'utilisation
- Pour Folia : utilise `ParticleTask` avec le scheduler régional approprié

## Contribution

Les contributions sont les bienvenues via Pull Requests sur GitHub.

**Licence** : GNU GPL v3.0

**À vérifier** : Documentation Javadoc manquante sur certaines classes utilitaires.
