package org.leralix.lib.utils.particles;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;

class ParticleTask implements Runnable {
    private final Player player;
    private int secondsLeft;
    private final Runnable drawAction;
    private ScheduledTask task;

    // Constructeur où la méthode à exécuter est passée en paramètre
    protected ParticleTask(Player player, int duration, Runnable drawAction) {
        this.player = player;
        this.secondsLeft = duration;
        this.drawAction = drawAction;
    }

    @Override
    public void run() {
        // Si le joueur n'est pas nul et que la durée restante est positive
        if (player == null || !player.isValid() || secondsLeft <= 0) {
            cancel();  // Annulation immédiate si conditions non remplies
            return;
        }

        // Exécuter l'action de dessin passée en paramètre
        drawAction.run();

        secondsLeft--;  // Décrémentation du temps restant
        if (secondsLeft <= 0) {
            cancel();  // Annulation lorsque le temps est écoulé
        }
    }

    protected void setTask(ScheduledTask task) {
        this.task = task;
    }

    private void cancel() {
        if (task != null) {
            task.cancel();
        }
    }
}
