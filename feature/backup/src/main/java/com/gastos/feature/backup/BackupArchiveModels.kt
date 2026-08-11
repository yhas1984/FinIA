package com.gastos.feature.backup

import com.gastos.domain.model.ChatMessageRecord
import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.repository.BackupDataset
import com.gastos.repository.RestorableSettings
import com.gastos.storage.InvoiceImageStorage
import kotlinx.serialization.Serializable

internal const val LEGACY_BACKUP_FORMAT_VERSION = 1
internal const val BACKUP_FORMAT_VERSION = 2
internal const val BACKUP_FILE_EXTENSION = "finai"
internal const val BACKUP_MIME_TYPE = "application/vnd.finai.backup"

@Serializable
internal data class EncryptedBackupHeader(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val databaseVersion: Int,
    val invoiceCount: Int,
    val productCount: Int,
    val incomeCount: Int,
    val imageCount: Int,
    val kdfIterations: Int,
    val salt: String,
    val keyIv: String,
    val wrappedKey: String,
    val payloadIv: String? = null
)

data class BackupPreview(
    val createdAt: Long,
    val appVersionName: String,
    val databaseVersion: Int,
    val invoiceCount: Int,
    val productCount: Int,
    val incomeCount: Int,
    val imageCount: Int
)

data class CloudBackupInfo(
    val fileId: String,
    val name: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val preview: BackupPreview
)

@Serializable
internal data class BackupPayloadDto(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val createdAt: Long,
    val invoices: List<InvoiceDto>,
    val products: List<ProductDto>,
    val incomes: List<IncomeDto>,
    val fiscalConfigs: List<FiscalConfigDto>,
    val chatMessages: List<ChatMessageDto>,
    val settings: RestorableSettingsDto
) {
    val imageFileNames: Set<String>
        get() = (invoices.mapNotNull { it.imageFileName } +
            incomes.mapNotNull { it.imageFileName }).toSet()

    fun toDataset(restoredImages: Map<String, String>): BackupDataset = BackupDataset(
        invoices = invoices.map { it.toDomain(restoredImages) },
        products = products.map(ProductDto::toDomain),
        incomes = incomes.map { it.toDomain(restoredImages) },
        fiscalConfigs = fiscalConfigs.map(FiscalConfigDto::toDomain),
        chatMessages = chatMessages.map(ChatMessageDto::toDomain)
    )
}

@Serializable
internal data class InvoiceDto(
    val id: Long,
    val fecha: Long,
    val proveedor: String,
    val tipo: String,
    val categoria: String?,
    val subcategoria: String? = null,
    val moneda: String,
    val total: Double,
    val ivaPercent: Double,
    val irpfPercent: Double,
    val paisCodigo: String,
    val nifEmisor: String?,
    val nifReceptor: String?,
    val imageFileName: String?,
    val driveFileId: String?,
    val driveWebViewLink: String?,
    val driveUploadPending: Boolean,
    val ocrRawText: String?,
    val notas: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(images: Map<String, String>): Invoice = Invoice(
        id = id,
        fecha = fecha,
        proveedor = proveedor,
        tipo = InvoiceType.valueOf(tipo),
        categoria = categoria,
        subcategoria = subcategoria,
        moneda = moneda,
        total = total,
        ivaPercent = ivaPercent,
        irpfPercent = irpfPercent,
        paisCodigo = paisCodigo,
        nifEmisor = nifEmisor,
        nifReceptor = nifReceptor,
        imagenUri = imageFileName?.let(images::get),
        driveFileId = driveFileId,
        driveWebViewLink = driveWebViewLink,
        driveUploadPending = driveUploadPending,
        ocrRawText = ocrRawText,
        notas = notas,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

@Serializable
internal data class ProductDto(
    val id: Long,
    val invoiceId: Long,
    val descripcion: String,
    val cantidad: Double,
    val precioUnitario: Double,
    val subtotal: Double,
    val ivaPercent: Double,
    val ivaAmount: Double,
    val createdAt: Long
) {
    fun toDomain(): Product = Product(
        id = id,
        invoiceId = invoiceId,
        descripcion = descripcion,
        cantidad = cantidad,
        precioUnitario = precioUnitario,
        subtotal = subtotal,
        ivaPercent = ivaPercent,
        ivaAmount = ivaAmount,
        createdAt = createdAt
    )
}

@Serializable
internal data class IncomeDto(
    val id: Long,
    val fecha: Long,
    val concepto: String,
    val monto: Double,
    val totalDevengado: Double,
    val totalNeto: Double,
    val moneda: String,
    val fuente: String?,
    val categoria: String?,
    val subcategoria: String? = null,
    val ivaPercent: Double,
    val irpfPercent: Double,
    val imageFileName: String?,
    val notas: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(images: Map<String, String>): Income = Income(
        id = id,
        fecha = fecha,
        concepto = concepto,
        monto = monto,
        totalDevengado = totalDevengado,
        totalNeto = totalNeto,
        moneda = moneda,
        fuente = fuente,
        categoria = categoria,
        subcategoria = subcategoria,
        ivaPercent = ivaPercent,
        irpfPercent = irpfPercent,
        imagenUri = imageFileName?.let(images::get),
        notas = notas,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

@Serializable
internal data class FiscalConfigDto(
    val paisCodigo: String,
    val nombrePais: String,
    val ivaRates: List<Double>,
    val irpfRate: Double?,
    val nifFormat: String,
    val nombreLeyFiscal: String
) {
    fun toDomain(): CountryFiscalConfig = CountryFiscalConfig(
        paisCodigo = paisCodigo,
        nombrePais = nombrePais,
        ivaRates = ivaRates,
        irpfRate = irpfRate,
        nifFormat = nifFormat,
        nombreLeyFiscal = nombreLeyFiscal
    )
}

@Serializable
internal data class ChatMessageDto(
    val id: Long,
    val role: String,
    val visibleText: String,
    val contextText: String?,
    val includeInContext: Boolean,
    val createdAt: Long
) {
    fun toDomain(): ChatMessageRecord = ChatMessageRecord(
        id = id,
        role = role,
        visibleText = visibleText,
        contextText = contextText,
        includeInContext = includeInContext,
        createdAt = createdAt
    )
}

@Serializable
internal data class RestorableSettingsDto(
    val systemInstructions: String,
    val defaultCurrency: String,
    val defaultCountry: String,
    val darkMode: String,
    val dashboardWidgetOrder: List<String> = emptyList(),
    val dashboardHiddenWidgets: List<String> = emptyList()
) {
    fun toDomain(): RestorableSettings = RestorableSettings(
        systemInstructions = systemInstructions,
        defaultCurrency = defaultCurrency,
        defaultCountry = defaultCountry,
        darkMode = darkMode,
        dashboardWidgetOrder = dashboardWidgetOrder,
        dashboardHiddenWidgets = dashboardHiddenWidgets
    )
}

internal fun BackupDataset.toDto(
    createdAt: Long,
    settings: RestorableSettings,
    imageStorage: InvoiceImageStorage
): BackupPayloadDto = BackupPayloadDto(
    createdAt = createdAt,
    invoices = invoices.map { invoice ->
        InvoiceDto(
            id = invoice.id,
            fecha = invoice.fecha,
            proveedor = invoice.proveedor,
            tipo = invoice.tipo.name,
            categoria = invoice.categoria,
            subcategoria = invoice.subcategoria,
            moneda = invoice.moneda,
            total = invoice.total,
            ivaPercent = invoice.ivaPercent,
            irpfPercent = invoice.irpfPercent,
            paisCodigo = invoice.paisCodigo,
            nifEmisor = invoice.nifEmisor,
            nifReceptor = invoice.nifReceptor,
            imageFileName = imageStorage.managedFile(invoice.imagenUri)?.name,
            driveFileId = invoice.driveFileId,
            driveWebViewLink = invoice.driveWebViewLink,
            driveUploadPending = invoice.driveUploadPending,
            ocrRawText = invoice.ocrRawText,
            notas = invoice.notas,
            createdAt = invoice.createdAt,
            updatedAt = invoice.updatedAt
        )
    },
    products = products.map { product ->
        ProductDto(
            id = product.id,
            invoiceId = product.invoiceId,
            descripcion = product.descripcion,
            cantidad = product.cantidad,
            precioUnitario = product.precioUnitario,
            subtotal = product.subtotal,
            ivaPercent = product.ivaPercent,
            ivaAmount = product.ivaAmount,
            createdAt = product.createdAt
        )
    },
    incomes = incomes.map { income ->
        IncomeDto(
            id = income.id,
            fecha = income.fecha,
            concepto = income.concepto,
            monto = income.monto,
            totalDevengado = income.totalDevengado,
            totalNeto = income.totalNeto,
            moneda = income.moneda,
            fuente = income.fuente,
            categoria = income.categoria,
            subcategoria = income.subcategoria,
            ivaPercent = income.ivaPercent,
            irpfPercent = income.irpfPercent,
            imageFileName = imageStorage.managedFile(income.imagenUri)?.name,
            notas = income.notas,
            createdAt = income.createdAt,
            updatedAt = income.updatedAt
        )
    },
    fiscalConfigs = fiscalConfigs.map { config ->
        FiscalConfigDto(
            paisCodigo = config.paisCodigo,
            nombrePais = config.nombrePais,
            ivaRates = config.ivaRates,
            irpfRate = config.irpfRate,
            nifFormat = config.nifFormat,
            nombreLeyFiscal = config.nombreLeyFiscal
        )
    },
    chatMessages = chatMessages.map { message ->
        ChatMessageDto(
            id = message.id,
            role = message.role,
            visibleText = message.visibleText,
            contextText = message.contextText,
            includeInContext = message.includeInContext,
            createdAt = message.createdAt
        )
    },
    settings = RestorableSettingsDto(
        systemInstructions = settings.systemInstructions,
        defaultCurrency = settings.defaultCurrency,
        defaultCountry = settings.defaultCountry,
        darkMode = settings.darkMode,
        dashboardWidgetOrder = settings.dashboardWidgetOrder,
        dashboardHiddenWidgets = settings.dashboardHiddenWidgets
    )
)
