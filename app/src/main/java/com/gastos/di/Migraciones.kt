package com.gastos.di

import androidx.room.migration.Migration

/**
 * Todas las migraciones de [com.gastos.local.database.AppDatabase], en orden y en un único sitio.
 *
 * Se usa en producción (`AppModule`) y en TODOS los tests instrumentados que abren la base desde un
 * esquema antiguo. El motivo de centralizarlo: cada test llevaba su propia lista parcial, y al subir
 * la base a la 18 esas listas dejaron de llegar a la versión actual → cuatro tests en rojo con
 * «A migration from X to 18 was required but not found» (CI del 22-oct-2026). Con una sola lista,
 * añadir la migración aquí la propaga a producción y a todas las pruebas a la vez.
 *
 * Regla: al subir la versión de `AppDatabase`, añade la migración AQUÍ (no en cada test).
 */
val MIGRACIONES_DE_APP: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
    MIGRATION_13_14,
    MIGRATION_14_15,
    MIGRATION_15_16,
    MIGRATION_16_17,
    MIGRATION_17_18
)
