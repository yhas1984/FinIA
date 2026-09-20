package com.gastos.di

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve historical values and relationships; unknown and mixed tax rates can now be NULL. */
val MIGRATION_13_14: Migration = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TEMP TABLE tax_migration_products AS SELECT * FROM products")
        db.execSQL("CREATE TEMP TABLE tax_migration_sequences AS SELECT name, seq FROM sqlite_sequence")
        db.execSQL("DROP TABLE products")
        db.execSQL("CREATE TABLE IF NOT EXISTS `invoices_taxes` (`taxesJson` TEXT NOT NULL DEFAULT '[]', `evidenceJson` TEXT, `documentKey` TEXT, `sourceSha256` TEXT, `documentUuid` TEXT NOT NULL DEFAULT '', `driveAccountId` TEXT, `driveContentHash` TEXT, `driveSyncError` TEXT, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fecha` INTEGER NOT NULL, `proveedor` TEXT NOT NULL, `tipo` TEXT NOT NULL, `categoria` TEXT, `subcategoria` TEXT, `moneda` TEXT NOT NULL, `total` REAL NOT NULL, `numeroFactura` TEXT, `baseImponible` REAL, `cuotaIva` REAL, `ivaPercent` REAL, `irpfPercent` REAL NOT NULL, `paisCodigo` TEXT NOT NULL, `nifEmisor` TEXT, `nifReceptor` TEXT, `imagenUri` TEXT, `driveFileId` TEXT, `driveWebViewLink` TEXT, `driveUploadPending` INTEGER NOT NULL DEFAULT 0, `ocrRawText` TEXT, `notas` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
        db.execSQL("INSERT INTO invoices_taxes (`evidenceJson`, `documentKey`, `sourceSha256`, `documentUuid`, `driveAccountId`, `driveContentHash`, `driveSyncError`, `id`, `fecha`, `proveedor`, `tipo`, `categoria`, `subcategoria`, `moneda`, `total`, `numeroFactura`, `baseImponible`, `cuotaIva`, `ivaPercent`, `irpfPercent`, `paisCodigo`, `nifEmisor`, `nifReceptor`, `imagenUri`, `driveFileId`, `driveWebViewLink`, `driveUploadPending`, `ocrRawText`, `notas`, `createdAt`, `updatedAt`) SELECT `evidenceJson`, `documentKey`, `sourceSha256`, `documentUuid`, `driveAccountId`, `driveContentHash`, `driveSyncError`, `id`, `fecha`, `proveedor`, `tipo`, `categoria`, `subcategoria`, `moneda`, `total`, `numeroFactura`, `baseImponible`, `cuotaIva`, `ivaPercent`, `irpfPercent`, `paisCodigo`, `nifEmisor`, `nifReceptor`, `imagenUri`, `driveFileId`, `driveWebViewLink`, `driveUploadPending`, `ocrRawText`, `notas`, `createdAt`, `updatedAt` FROM invoices")
        db.execSQL("DROP TABLE invoices")
        db.execSQL("ALTER TABLE invoices_taxes RENAME TO invoices")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_invoices_documentUuid` ON `invoices` (`documentUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_documentKey` ON `invoices` (`documentKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_sourceSha256` ON `invoices` (`sourceSha256`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `incomes_taxes` (`taxesJson` TEXT NOT NULL DEFAULT '[]', `evidenceJson` TEXT, `documentKey` TEXT, `sourceSha256` TEXT, `documentUuid` TEXT NOT NULL DEFAULT '', `driveAccountId` TEXT, `driveContentHash` TEXT, `driveSyncError` TEXT, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fecha` INTEGER NOT NULL, `concepto` TEXT NOT NULL, `monto` REAL NOT NULL, `totalDevengado` REAL NOT NULL, `totalNeto` REAL NOT NULL, `moneda` TEXT NOT NULL, `fuente` TEXT, `categoria` TEXT, `subcategoria` TEXT, `ivaPercent` REAL, `irpfPercent` REAL NOT NULL, `imagenUri` TEXT, `driveFileId` TEXT, `driveWebViewLink` TEXT, `driveUploadPending` INTEGER NOT NULL DEFAULT 0, `notas` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
        db.execSQL("INSERT INTO incomes_taxes (`evidenceJson`, `documentKey`, `sourceSha256`, `documentUuid`, `driveAccountId`, `driveContentHash`, `driveSyncError`, `id`, `fecha`, `concepto`, `monto`, `totalDevengado`, `totalNeto`, `moneda`, `fuente`, `categoria`, `subcategoria`, `ivaPercent`, `irpfPercent`, `imagenUri`, `driveFileId`, `driveWebViewLink`, `driveUploadPending`, `notas`, `createdAt`, `updatedAt`) SELECT `evidenceJson`, `documentKey`, `sourceSha256`, `documentUuid`, `driveAccountId`, `driveContentHash`, `driveSyncError`, `id`, `fecha`, `concepto`, `monto`, `totalDevengado`, `totalNeto`, `moneda`, `fuente`, `categoria`, `subcategoria`, `ivaPercent`, `irpfPercent`, `imagenUri`, `driveFileId`, `driveWebViewLink`, `driveUploadPending`, `notas`, `createdAt`, `updatedAt` FROM incomes")
        db.execSQL("DROP TABLE incomes")
        db.execSQL("ALTER TABLE incomes_taxes RENAME TO incomes")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_incomes_documentUuid` ON `incomes` (`documentUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_incomes_documentKey` ON `incomes` (`documentKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_incomes_sourceSha256` ON `incomes` (`sourceSha256`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `products` (`taxesJson` TEXT NOT NULL DEFAULT '[]', `pricesIncludeTax` INTEGER NOT NULL DEFAULT 1, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `invoiceId` INTEGER NOT NULL, `descripcion` TEXT NOT NULL, `cantidad` REAL NOT NULL, `precioUnitario` REAL NOT NULL, `subtotal` REAL NOT NULL, `ivaPercent` REAL, `ivaAmount` REAL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("INSERT INTO products (`pricesIncludeTax`, `id`, `invoiceId`, `descripcion`, `cantidad`, `precioUnitario`, `subtotal`, `ivaPercent`, `ivaAmount`, `createdAt`) SELECT `pricesIncludeTax`, `id`, `invoiceId`, `descripcion`, `cantidad`, `precioUnitario`, `subtotal`, `ivaPercent`, `ivaAmount`, `createdAt` FROM tax_migration_products")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_invoiceId` ON `products` (`invoiceId`)")
        db.execSQL("UPDATE sqlite_sequence SET seq = MAX(seq, COALESCE((SELECT seq FROM tax_migration_sequences WHERE name = sqlite_sequence.name), seq))")
        db.execSQL("DROP TABLE tax_migration_products")
        db.execSQL("DROP TABLE tax_migration_sequences")
    }
}
