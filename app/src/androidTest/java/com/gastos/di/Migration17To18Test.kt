package com.gastos.di

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.local.database.AppDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migración 17 → 18: la configuración fiscal por país gana periodo, nombre de declaración, plazos y
 * tipos de retención.
 *
 * La base de partida se reconstruye **desde el esquema exportado `17.json`** en lugar de escribir a
 * mano las tablas: Room valida TODAS las tablas al migrar, así que una base de prueba a medias hace
 * fallar el test por motivos que no son la migración. Y como esta migración se ejecuta sobre los
 * datos reales de quien ya tiene la app instalada, lo que importa comprobar es que no se pierde nada
 * y que los `DEFAULT` de las columnas nuevas son los que declara la entidad.
 *
 * (`MigrationTestHelper` de room-testing no se puede usar todavía: 2.8.4 necesita
 * `androidx.sqlite.driver.SupportSQLiteDriver` de androidx.sqlite 2.6 y el proyecto fija la 2.5.1.)
 */
@RunWith(AndroidJUnit4::class)
class Migration17To18Test {

    private lateinit var context: Context
    private val databaseName = "migration-17-18-test"
    private val schemaAsset = "com.gastos.local.database.AppDatabase/17.json"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    /** Crea una base v17 completa a partir del esquema exportado y le mete las filas indicadas. */
    private fun crearBaseV17(filas: List<String>) {
        // Los esquemas son assets del APK de PRUEBAS (sourceSets androidTest), así que hay que
        // leerlos con el contexto de la instrumentación, no con el de la app bajo test.
        val assetsPruebas = InstrumentationRegistry.getInstrumentation().context.assets
        val json = assetsPruebas.open(schemaAsset).bufferedReader().use { it.readText() }
        val database = JSONObject(json).getJSONObject("database")
        val entidades = database.getJSONArray("entities")

        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            for (i in 0 until entidades.length()) {
                val entidad = entidades.getJSONObject(i)
                val tabla = entidad.getString("tableName")
                db.execSQL(entidad.getString("createSql").replace("\${TABLE_NAME}", tabla))
                val indices = entidad.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tabla)
                    )
                }
            }
            filas.forEach { db.execSQL(it) }
            db.version = 17
        }
    }

    private fun abrirMigrada(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_17_18)
            .build()

    private fun filaEspana() =
        "INSERT INTO country_fiscal_config (paisCodigo, nombrePais, ivaRates, irpfRate, " +
            "nifFormat, nombreLeyFiscal) VALUES ('ES','España','0,4,10,21',15.0,'X-XXXXXXXX-X','IVA')"

    private fun filaMexico() =
        "INSERT INTO country_fiscal_config (paisCodigo, nombrePais, ivaRates, irpfRate, " +
            "nifFormat, nombreLeyFiscal) VALUES ('MX','México','0,8,16',NULL,'XXXX-XX-XXXX','IVA')"

    @Test
    fun migra_sin_perder_datos_y_deja_espana_con_reglas_verificadas() {
        crearBaseV17(listOf(filaEspana(), filaMexico()))

        val database = abrirMigrada()
        database.openHelper.writableDatabase.use { sqlite ->
            sqlite.query(
                "SELECT nombrePais, tipoPeriodo, nombreDeclaracion, plazos, tiposRetencion " +
                    "FROM country_fiscal_config WHERE paisCodigo = 'ES'"
            ).use { cursor ->
                assertTrue("España debería seguir en la tabla", cursor.moveToFirst())
                assertEquals("España", cursor.getString(0))
                assertEquals("QUARTERLY", cursor.getString(1))
                assertEquals("modelo 303", cursor.getString(2))
                assertEquals(
                    "1T=1-20 abril;2T=1-20 julio;3T=1-20 octubre;4T=1-30 enero",
                    cursor.getString(3)
                )
                assertEquals("7", cursor.getString(4))
            }

            // Cualquier otro país: sin reglas verificadas y sin formulario nombrado.
            sqlite.query(
                "SELECT nombrePais, tipoPeriodo, nombreDeclaracion, plazos, tiposRetencion " +
                    "FROM country_fiscal_config WHERE paisCodigo = 'MX'"
            ).use { cursor ->
                assertTrue("México debería seguir en la tabla", cursor.moveToFirst())
                assertEquals("México", cursor.getString(0))
                assertEquals("USER_DEFINED", cursor.getString(1))
                assertEquals("", cursor.getString(2))
                assertEquals("", cursor.getString(3))
                assertEquals("", cursor.getString(4))
            }

            sqlite.query("SELECT COUNT(*) FROM country_fiscal_config").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("No se ha perdido ninguna fila", 2, cursor.getInt(0))
            }
        }
        database.close()
    }

    @Test
    fun los_default_de_las_columnas_nuevas_son_los_de_la_entidad() {
        crearBaseV17(emptyList())

        val database = abrirMigrada()
        database.openHelper.writableDatabase.use { sqlite ->
            val porDefecto = mutableMapOf<String, String?>()
            sqlite.query("PRAGMA table_info(country_fiscal_config)").use { cursor ->
                val nombre = cursor.getColumnIndex("name")
                val dflt = cursor.getColumnIndex("dflt_value")
                while (cursor.moveToNext()) porDefecto[cursor.getString(nombre)] = cursor.getString(dflt)
            }
            // Room compara estos DEFAULT con los de @ColumnInfo(defaultValue = …): si divergen, la
            // app falla al abrir la base después de actualizar.
            assertEquals("'USER_DEFINED'", porDefecto["tipoPeriodo"])
            assertEquals("''", porDefecto["nombreDeclaracion"])
            assertEquals("''", porDefecto["plazos"])
            assertEquals("''", porDefecto["tiposRetencion"])
        }
        database.close()
    }

    @Test
    fun conserva_tipos_y_nombre_del_impuesto_de_cada_pais() {
        crearBaseV17(
            listOf(
                "INSERT INTO country_fiscal_config (paisCodigo, nombrePais, ivaRates, irpfRate, " +
                    "nifFormat, nombreLeyFiscal) VALUES ('PE','Perú','0,18',NULL,'XXXXXXXXXX','IGV')",
                "INSERT INTO country_fiscal_config (paisCodigo, nombrePais, ivaRates, irpfRate, " +
                    "nifFormat, nombreLeyFiscal) VALUES ('US','Estados Unidos','0',NULL,'XXX-XX-XXXX','Sales Tax')"
            )
        )

        val database = abrirMigrada()
        database.openHelper.writableDatabase.use { sqlite ->
            sqlite.query(
                "SELECT ivaRates, nombreLeyFiscal, tipoPeriodo FROM country_fiscal_config " +
                    "WHERE paisCodigo = 'PE'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("0,18", cursor.getString(0))
                assertEquals("IGV", cursor.getString(1))
                assertEquals("USER_DEFINED", cursor.getString(2))
            }
            sqlite.query(
                "SELECT nombreLeyFiscal FROM country_fiscal_config WHERE paisCodigo = 'US'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Sales Tax", cursor.getString(0))
            }
        }
        database.close()
    }
}
