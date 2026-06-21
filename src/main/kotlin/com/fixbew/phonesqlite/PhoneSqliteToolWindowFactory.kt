package com.fixbew.phonesqlite

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.GridLayout
import java.io.File
import java.sql.DriverManager
import javax.swing.*
import javax.swing.table.DefaultTableModel
import java.util.Vector

class PhoneSqliteToolWindowFactory : ToolWindowFactory {

    private var currentDbFile: File? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val packageField = JTextField("com.example.app")
        val dbNameField = JTextField("app.db")

        val loadButton = JButton("Load DB")
        val refreshTablesButton = JButton("Refresh tables")
        val openTableButton = JButton("Open selected table")

        val tablesListModel = DefaultListModel<String>()
        val tablesList = JList(tablesListModel)

        val tableModel = DefaultTableModel()
        val tableView = JTable(tableModel)

        val logArea = JTextArea()
        logArea.isEditable = false

        val inputPanel = JPanel(GridLayout(2, 2))
        inputPanel.add(JLabel("Package name:"))
        inputPanel.add(packageField)
        inputPanel.add(JLabel("Database name:"))
        inputPanel.add(dbNameField)

        val buttonsPanel = JPanel(GridLayout(1, 3))
        buttonsPanel.add(loadButton)
        buttonsPanel.add(refreshTablesButton)
        buttonsPanel.add(openTableButton)

        val topPanel = JPanel(BorderLayout())
        topPanel.add(inputPanel, BorderLayout.CENTER)
        topPanel.add(buttonsPanel, BorderLayout.SOUTH)

        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JScrollPane(tablesList),
            JScrollPane(tableView)
        )
        splitPane.dividerLocation = 180

        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(splitPane, BorderLayout.CENTER)
        mainPanel.add(JScrollPane(logArea), BorderLayout.SOUTH)

        loadButton.addActionListener {
            val packageName = packageField.text.trim()
            val dbName = dbNameField.text.trim()

            runInBackground(
                logArea,
                task = {
                    val dbFile = pullDatabaseFromPhone(packageName, dbName)
                    currentDbFile = dbFile
                    readTables(dbFile)
                },
                onSuccess = { tables ->
                    tablesListModel.clear()
                    tables.forEach { tablesListModel.addElement(it) }

                    logArea.text = buildString {
                        appendLine("DB loaded:")
                        appendLine(currentDbFile?.absolutePath)
                        appendLine()
                        appendLine("Tables found: ${tables.size}")
                    }
                }
            )
        }

        refreshTablesButton.addActionListener {
            val dbFile = currentDbFile

            if (dbFile == null) {
                logArea.text = "Load database first"
                return@addActionListener
            }

            runInBackground(
                logArea,
                task = {
                    readTables(dbFile)
                },
                onSuccess = { tables ->
                    tablesListModel.clear()
                    tables.forEach { tablesListModel.addElement(it) }
                    logArea.text = "Tables refreshed: ${tables.size}"
                }
            )
        }

        openTableButton.addActionListener {
            val dbFile = currentDbFile
            val selectedTable = tablesList.selectedValue

            if (dbFile == null) {
                logArea.text = "Load database first"
                return@addActionListener
            }

            if (selectedTable == null) {
                logArea.text = "Select table first"
                return@addActionListener
            }

            runInBackground(
                logArea,
                task = {
                    readRows(dbFile, selectedTable, 100)
                },
                onSuccess = { result ->
                    tableModel.setDataVector(result.rows, result.columns)
                    logArea.text = "Opened table: $selectedTable, rows: ${result.rows.size}"
                }
            )
        }

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun <T> runInBackground(
        logArea: JTextArea,
        task: () -> T,
        onSuccess: (T) -> Unit
    ) {
        logArea.text = "Working..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = task()

                SwingUtilities.invokeLater {
                    onSuccess(result)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    logArea.text = "Error:\n${e.message}"
                }
            }
        }
    }

    private fun pullDatabaseFromPhone(packageName: String, dbName: String): File {
        if (packageName.isBlank()) {
            throw IllegalArgumentException("Package name is empty")
        }

        if (dbName.isBlank()) {
            throw IllegalArgumentException("Database name is empty")
        }

        val tempDir = createTempDir(prefix = "phone-sqlite-")
        val dbFile = File(tempDir, dbName)

        val adb = findAdb()

        pullFileViaRunAs(
            adb = adb,
            packageName = packageName,
            remotePath = "databases/$dbName",
            outputFile = dbFile,
            required = true
        )

        // Если SQLite работает в WAL-режиме, рядом могут быть эти файлы.
        // Если их нет — это не ошибка.
        pullFileViaRunAs(
            adb = adb,
            packageName = packageName,
            remotePath = "databases/$dbName-wal",
            outputFile = File(tempDir, "$dbName-wal"),
            required = false
        )

        pullFileViaRunAs(
            adb = adb,
            packageName = packageName,
            remotePath = "databases/$dbName-shm",
            outputFile = File(tempDir, "$dbName-shm"),
            required = false
        )

        return dbFile
    }

    private fun pullFileViaRunAs(
        adb: String,
        packageName: String,
        remotePath: String,
        outputFile: File,
        required: Boolean
    ) {
        val process = ProcessBuilder(
            adb,
            "exec-out",
            "run-as",
            packageName,
            "cat",
            remotePath
        )
            .redirectOutput(outputFile)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        val exitCode = process.waitFor()
        val error = process.errorStream.bufferedReader().readText()

        if (exitCode != 0) {
            outputFile.delete()

            if (required) {
                throw RuntimeException(
                    """
                    Cannot pull file: $remotePath
                    
                    adb error:
                    $error
                    
                    Possible reasons:
                    - app is not debuggable
                    - wrong package name
                    - wrong database name
                    - device is not connected
                    - adb is not available
                    """.trimIndent()
                )
            }
        }
    }

    private fun findAdb(): String {
        val androidHome = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")

        if (androidHome != null) {
            val adbFile = File(androidHome, "platform-tools/adb.exe")
            if (adbFile.exists()) {
                return adbFile.absolutePath
            }

            val adbUnix = File(androidHome, "platform-tools/adb")
            if (adbUnix.exists()) {
                return adbUnix.absolutePath
            }
        }

        // fallback: если adb есть в PATH
        return "adb"
    }

    private fun readTables(dbFile: File): List<String> {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT name
                    FROM sqlite_master
                    WHERE type = 'table'
                      AND name NOT LIKE 'android_metadata'
                      AND name NOT LIKE 'sqlite_%'
                    ORDER BY name
                    """.trimIndent()
                ).use { resultSet ->
                    val tables = mutableListOf<String>()

                    while (resultSet.next()) {
                        tables.add(resultSet.getString("name"))
                    }

                    return tables
                }
            }
        }
    }

    private fun readRows(dbFile: File, tableName: String, limit: Int): QueryResult {
        val safeTableName = quoteSqliteIdentifier(tableName)
        
        Class.forName("org.sqlite.JDBC")
        
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM $safeTableName LIMIT $limit").use { resultSet ->
                    val meta = resultSet.metaData
                    val columnCount = meta.columnCount

                    val columns = Vector<String>()
                    for (i in 1..columnCount) {
                        columns.add(meta.getColumnName(i))
                    }

                    val rows = Vector<Vector<Any?>>()

                    while (resultSet.next()) {
                        val row = Vector<Any?>()

                        for (i in 1..columnCount) {
                            row.add(resultSet.getObject(i))
                        }

                        rows.add(row)
                    }

                    return QueryResult(columns, rows)
                }
            }
        }
    }

    private fun quoteSqliteIdentifier(identifier: String): String {
        return "\"" + identifier.replace("\"", "\"\"") + "\""
    }

    private data class QueryResult(
        val columns: Vector<String>,
        val rows: Vector<Vector<Any?>>
    )
}