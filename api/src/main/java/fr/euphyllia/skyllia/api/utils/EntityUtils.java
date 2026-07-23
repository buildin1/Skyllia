package fr.euphyllia.skyllia.api.utils;

import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;

public class EntityUtils {

    public static boolean isMonster(EntityType entityType) {
        Class<? extends Entity> entityClass = entityType.getEntityClass();
        return entityClass != null && Monster.class.isAssignableFrom(entityClass);
    }

    public static boolean isPassif(EntityType entityType) {
        Class<? extends Entity> entityClass = entityType.getEntityClass();
        return entityClass != null && (Animals.class.isAssignableFrom(entityType.getEntityClass()) ||
                Ambient.class.isAssignableFrom(entityType.getEntityClass()));
    }
}
