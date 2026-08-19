package org.leralix.lib.utils.config;

import java.util.Collection;

public class PluginSideBlacklist {

    private boolean enabled;
    private int indentation;
    private Collection<String> blacklist;

    public PluginSideBlacklist(Collection<String> blackList) {
        enabled = false;
        indentation = 0;
        this.blacklist = blackList;
    }

    /**
     * CN-FIX-CONFIG-LIVE — une section en liste noire peut en suivre une autre.
     *
     * <p>L'ancienne forme sortait de la méthode dès qu'elle quittait une section
     * ({@code return enabled;}) sans jamais re-tester la ligne courante contre la liste noire. Deux
     * sections consécutives — {@code kits:} puis {@code arenas:} dans {@code modules/pvp/arenas.yml}
     * — suffisaient donc à désarmer la garde définitivement : à partir de {@code arenas:}, toutes
     * les lignes du fichier de référence redevenaient éligibles à l'écriture, alors que le contenu
     * du disque venait déjà d'être recopié verbatim. Résultat : un bloc {@code colisee} entier
     * ajouté <b>à chaque démarrage</b> (62 blocs constatés en production).
     *
     * <p>La sortie de section et l'entrée dans la suivante sont maintenant évaluées dans le même
     * appel.
     */
    public boolean isInBackListPart(String currentKey){

        if(enabled){
            if(getNbIndentation(currentKey) > indentation || currentKey.isBlank()){
                return true; //Still inside the blacklisted section: the plugin line is skipped.
            }
            enabled = false; //Left the section — fall through: the line may open the NEXT one.
        }

        if(containsKey(blacklist, currentKey)){
            enabled = true;
            indentation = getNbIndentation(currentKey);
            return false; //We allow the blacklisted word to be written. Only what comes after is passed
        }
        return false;
    }


    int getNbIndentation(String pluginFileLine) {
        return ConfigUtil.getNbIndentation(pluginFileLine);
    }

    static boolean containsKey(Collection<String> blackListedWords, String key) {
        return ConfigUtil.containsKey(blackListedWords, key);
    }

}
