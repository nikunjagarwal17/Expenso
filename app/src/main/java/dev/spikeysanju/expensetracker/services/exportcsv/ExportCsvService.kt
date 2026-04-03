package dev.spikeysanju.expensetracker.services.exportcsv

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import dev.spikeysanju.expensetracker.model.Account
import dev.spikeysanju.expensetracker.model.Transaction
import kotlinx.coroutines.flow.flow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Row data for XLSX export.
 */
data class ExportRow(
    val date: String,
    val balance: Double,
    val accountBalances: Map<String, Double>,
    val expense: String,
    val income: String,
    val accountName: String,
    val title: String,
    val note: String,
    val isIncome: Boolean
)

/**
 * Generates an XLSX file manually using ZipOutputStream + Office Open XML.
 * Zero external dependencies. Supports color-coded rows.
 */
class ExportCsvService @Inject constructor(
    private val appContext: Context
) {

    private val inputDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val outputDateFormat = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())

    @WorkerThread
    fun writeToXlsx(
        xlsxFileUri: Uri,
        transactions: List<Transaction>,
        accounts: List<Account>
    ) = flow<Uri> {
        val accountMap = accounts.associateBy({ it.id }, { it.name })
        val sortedAccountNames = accounts.map { it.name }.sorted()

        // Sort by date (dd/MM/yyyy) ASC then createdAt ASC for oldest-first export
        val sortedTransactions = transactions.sortedWith(
            compareBy<Transaction> {
                try { inputDateFormat.parse(it.date)?.time ?: 0L } catch (e: Exception) { 0L }
            }.thenBy { it.createdAt }
        )

        // Build row data with per-account running balances
        val accountRunningBalances = mutableMapOf<String, Double>()
        accounts.forEach { accountRunningBalances[it.id] = 0.0 }
        val rows = sortedTransactions.map { transaction ->
            when (transaction.transactionType) {
                "Income" -> accountRunningBalances[transaction.accountId] =
                    (accountRunningBalances[transaction.accountId] ?: 0.0) + transaction.amount
                "Expense" -> accountRunningBalances[transaction.accountId] =
                    (accountRunningBalances[transaction.accountId] ?: 0.0) - transaction.amount
            }
            val totalBalance = accountRunningBalances.values.sum()
            val accountBalancesSnapshot = sortedAccountNames.associateWith { name ->
                val accountId = accounts.find { it.name == name }?.id ?: ""
                accountRunningBalances[accountId] ?: 0.0
            }
            val formattedDate = try {
                val parsed = inputDateFormat.parse(transaction.date)
                if (parsed != null) outputDateFormat.format(parsed) else transaction.date
            } catch (e: Exception) {
                transaction.date
            }
            ExportRow(
                date = formattedDate,
                balance = totalBalance,
                accountBalances = accountBalancesSnapshot,
                expense = if (transaction.transactionType == "Expense") transaction.amount.toString() else "",
                income = if (transaction.transactionType == "Income") transaction.amount.toString() else "",
                accountName = accountMap[transaction.accountId] ?: "Unknown",
                title = transaction.title,
                note = transaction.note,
                isIncome = transaction.transactionType == "Income"
            )
        }

        val headers = listOf("Date", "Expense", "Income", "Total Balance", "Title", "Spent from") +
            (if (rows.any { it.note.isNotEmpty() }) listOf("Note") else emptyList()) +
            sortedAccountNames
        val hasNoteColumn = rows.any { it.note.isNotEmpty() }

        // Collect shared strings
        val sharedStrings = mutableListOf<String>()
        val stringIndexMap = mutableMapOf<String, Int>()
        fun idx(value: String): Int = stringIndexMap.getOrPut(value) {
            sharedStrings.add(value)
            sharedStrings.size - 1
        }
        headers.forEach { idx(it) }
        rows.forEach { row ->
            idx(row.date)
            if (row.expense.isNotEmpty()) idx(row.expense)
            if (row.income.isNotEmpty()) idx(row.income)
            idx(row.accountName)
            idx(row.title)
            if (row.note.isNotEmpty()) idx(row.note)
        }

        val fileDescriptor = appContext.contentResolver.openFileDescriptor(xlsxFileUri, "w")
            ?: throw IllegalStateException("failed to read fileDescriptor")

        fileDescriptor.use { pfd ->
            val outputStream = java.io.FileOutputStream(pfd.fileDescriptor)
            val zip = ZipOutputStream(outputStream)

            fun writeEntry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            writeEntry("[Content_Types].xml", contentTypesXml())
            writeEntry("_rels/.rels", relsXml())
            writeEntry("xl/_rels/workbook.xml.rels", workbookRelsXml())
            writeEntry("xl/workbook.xml", workbookXml())
            writeEntry("xl/styles.xml", stylesXml())
            writeEntry("xl/sharedStrings.xml", sharedStringsXml(sharedStrings))
            writeEntry("xl/worksheets/sheet1.xml", sheetXml(headers, rows, stringIndexMap, sortedAccountNames, hasNoteColumn))

            zip.close()
            outputStream.close()
            emit(xlsxFileUri)
        }
    }

    private fun sheetXml(
        headers: List<String>,
        rows: List<ExportRow>,
        si: Map<String, Int>,
        sortedAccountNames: List<String>,
        hasNoteColumn: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("<cols>")
        // Dynamic widths: Date=18, Expense=12, Income=12, TotalBalance=14, Title=20, SpentFrom=16, Note=30(if present), per-account=14
        val widths = mutableListOf(18, 12, 12, 14, 20, 16)
        if (hasNoteColumn) widths.add(30)
        sortedAccountNames.forEach { _ -> widths.add(14) }
        widths.forEachIndexed { i, w ->
            sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
        }
        sb.append("</cols><sheetData>")

        // Header row — style 1 (bold)
        sb.append("""<row r="1">""")
        headers.forEachIndexed { c, h ->
            sb.append("""<c r="${colRef(c)}1" s="1" t="s"><v>${si[h]}</v></c>""")
        }
        sb.append("</row>")

        // Column index offsets: Date=0, Expense=1, Income=2, TotalBalance=3, Title=4, SpentFrom=5, Note=6(if present), accounts after
        val expenseCol = 1
        val incomeCol = 2
        val totalBalanceCol = 3
        val titleCol = 4
        val spentFromCol = 5
        val noteCol = if (hasNoteColumn) 6 else -1
        val accountOffset = if (hasNoteColumn) 7 else 6

        // Data rows — style 2 (green/income) or 3 (red/expense)
        rows.forEachIndexed { i, row ->
            val r = i + 2
            val s = if (row.isIncome) 2 else 3
            sb.append("""<row r="$r">""")
            // Date
            sb.append("""<c r="${colRef(0)}$r" s="$s" t="s"><v>${si[row.date]}</v></c>""")
            // Expense
            if (row.expense.isNotEmpty()) {
                sb.append("""<c r="${colRef(expenseCol)}$r" s="$s" t="s"><v>${si[row.expense]}</v></c>""")
            } else {
                sb.append("""<c r="${colRef(expenseCol)}$r" s="$s"/>""")
            }
            // Income
            if (row.income.isNotEmpty()) {
                sb.append("""<c r="${colRef(incomeCol)}$r" s="$s" t="s"><v>${si[row.income]}</v></c>""")
            } else {
                sb.append("""<c r="${colRef(incomeCol)}$r" s="$s"/>""")
            }
            // Total Balance (numeric)
            sb.append("""<c r="${colRef(totalBalanceCol)}$r" s="$s"><v>${row.balance}</v></c>""")
            // Title
            sb.append("""<c r="${colRef(titleCol)}$r" s="$s" t="s"><v>${si[row.title]}</v></c>""")
            // Spent from (Account Name)
            sb.append("""<c r="${colRef(spentFromCol)}$r" s="$s" t="s"><v>${si[row.accountName]}</v></c>""")
            // Note (only if column exists)
            if (hasNoteColumn && noteCol >= 0) {
                if (row.note.isNotEmpty()) {
                    sb.append("""<c r="${colRef(noteCol)}$r" s="$s" t="s"><v>${si[row.note]}</v></c>""")
                } else {
                    sb.append("""<c r="${colRef(noteCol)}$r" s="$s"/>""")
                }
            }
            // Per-account balances (numeric)
            sortedAccountNames.forEachIndexed { j, accountName ->
                val bal = row.accountBalances[accountName] ?: 0.0
                sb.append("""<c r="${colRef(accountOffset + j)}$r" s="$s"><v>$bal</v></c>""")
            }
            sb.append("</row>")
        }

        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun colRef(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    private fun escapeXml(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun sharedStringsXml(strings: List<String>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${strings.size}" uniqueCount="${strings.size}">""")
        strings.forEach { sb.append("<si><t>${escapeXml(it)}</t></si>") }
        sb.append("</sst>")
        return sb.toString()
    }

    // ── Static XLSX scaffolding XML ──

    private fun contentTypesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
</Types>"""

    private fun relsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbookRelsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>"""

    private fun workbookXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets><sheet name="Transactions" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    private fun stylesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><name val="Calibri"/></font>
  </fonts>
  <fills count="4">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFC6EFCE"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFFFC7CE"/></patternFill></fill>
  </fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="4">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
    <xf numFmtId="0" fontId="0" fillId="2" borderId="0" xfId="0" applyFill="1"/>
    <xf numFmtId="0" fontId="0" fillId="3" borderId="0" xfId="0" applyFill="1"/>
  </cellXfs>
</styleSheet>"""
}
