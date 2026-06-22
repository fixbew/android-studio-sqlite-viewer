package com.fixbew.phonesqlite

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.sqlite.SQLiteDataSource
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Vector
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

class PhoneSqliteToolWindowFactory : ToolWindowFactory {
    private var isAddingSqlTab = false
    private var isRestoringSqlTabs = false

    private val plusTabTitle = "+"
    private val sqlEditorKey = "sqlEditor"
    private val sqlTabsCountKey = "phoneSqlite.sqlTabs.count"
    private val sqlTabTextKeyPrefix = "phoneSqlite.sqlTabs.text."
    private val selectedSqlTabKey = "phoneSqlite.sqlTabs.selected"

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val deviceCombo = JComboBox<DeviceItem>()
        val refreshDevicesButton = JButton("Refresh")

        val packageField = JTextField("com.example.app")

        val filePathField = JTextField()
        val browseAppFilesButton = JButton("Browse")

        val addSqlTabButton = JButton("Add SQL tab")
        val deleteSqlTabButton = JButton("Delete tab")
        val runButton = JButton("Run SQL")

        val queryTabs = JTabbedPane()
        val resultTableModel = DefaultTableModel()
        val resultTable = JTable(resultTableModel)

        val logArea = JTextArea(5, 30)
        logArea.isEditable = false

        val menuPanel = JPanel(GridBagLayout())

        addRow(
            panel = menuPanel,
            row = 0,
            label = "1. Emulator/device:",
            field = deviceCombo,
            button = refreshDevicesButton
        )

        addRow(
            panel = menuPanel,
            row = 1,
            label = "2. Package:",
            field = packageField,
            button = null
        )

        addRow(
            panel = menuPanel,
            row = 2,
            label = "3. Database file:",
            field = filePathField,
            button = browseAppFilesButton
        )

        val sqlToolbar = JPanel()
        sqlToolbar.add(addSqlTabButton)
        sqlToolbar.add(deleteSqlTabButton)
        sqlToolbar.add(runButton)

        val sqlPanel = JPanel(BorderLayout())
        sqlPanel.add(queryTabs, BorderLayout.CENTER)
        sqlPanel.add(sqlToolbar, BorderLayout.SOUTH)

        val sqlAndResultSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            sqlPanel,
            JScrollPane(resultTable)
        )
        sqlAndResultSplit.dividerLocation = 280

        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(menuPanel, BorderLayout.NORTH)
        mainPanel.add(sqlAndResultSplit, BorderLayout.CENTER)

        val rootSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            mainPanel,
            JScrollPane(logArea)
        )
        rootSplit.dividerLocation = 640

        setupSqlTabs(project, queryTabs)

        refreshDevicesButton.addActionListener {
            runInBackground(
                logArea = logArea,
                task = { listDevices() },
                onSuccess = { devices ->
                    deviceCombo.removeAllItems()

                    devices.forEach {
                        deviceCombo.addItem(it)
                    }

                    logArea.text = "Devices found: ${devices.size}"
                }
            )
        }

        browseAppFilesButton.addActionListener {
            val device = deviceCombo.selectedItem as? DeviceItem
            val packageName = packageField.text.trim()

            if (device == null) {
                logArea.text = "Select emulator/device first"
                return@addActionListener
            }

            if (packageName.isBlank()) {
                logArea.text = "Package name is empty"
                return@addActionListener
            }

            runInBackground(
                logArea = logArea,
                task = {
                    getAppRootDirectory(
                        deviceSerial = device.serial,
                        packageName = packageName
                    )
                },
                onSuccess = { appRoot ->
                    val initialPath = getExplorerInitialDirectory(
                        path = filePathField.text.trim(),
                        fallbackDirectory = "$appRoot/databases"
                    )

                    showAppFileExplorer(
                        deviceSerial = device.serial,
                        packageName = packageName,
                        appRoot = appRoot,
                        initialPath = initialPath,
                        logArea = logArea
                    ) { selectedPath ->
                        filePathField.text = selectedPath
                    }
                }
            )
        }

        addSqlTabButton.addActionListener {
            addSqlTab(
                project = project,
                queryTabs = queryTabs,
                initialSql = defaultSql(),
                selectNewTab = true
            )
        }

        deleteSqlTabButton.addActionListener {
            removeActiveSqlTab(
                project = project,
                queryTabs = queryTabs,
                logArea = logArea
            )
        }

        runButton.addActionListener {
            val device = deviceCombo.selectedItem as? DeviceItem
            val packageName = packageField.text.trim()
            val deviceFilePath = filePathField.text.trim()
            val sql = getActiveSql(queryTabs)

            if (device == null) {
                logArea.text = "Select emulator/device first"
                return@addActionListener
            }

            if (packageName.isBlank()) {
                logArea.text = "Package name is empty"
                return@addActionListener
            }

            if (deviceFilePath.isBlank()) {
                logArea.text = "Select database file first"
                return@addActionListener
            }

            if (sql.isBlank()) {
                logArea.text = "SQL tab is empty"
                return@addActionListener
            }

            runInBackground(
                logArea = logArea,
                task = {
                    val localDbFile = pullAppFileFromDevice(
                        deviceSerial = device.serial,
                        packageName = packageName,
                        deviceFilePath = deviceFilePath
                    )

                    executeSql(
                        dbFile = localDbFile,
                        sql = sql
                    )
                },
                onSuccess = { result ->
                    resultTableModel.setDataVector(result.rows, result.columns)
                    logArea.text = result.message
                }
            )
        }

        val content = ContentFactory.getInstance().createContent(rootSplit, "", false)
        toolWindow.contentManager.addContent(content)

        SwingUtilities.invokeLater {
            refreshDevicesButton.doClick()
        }
    }

    private fun addRow(
        panel: JPanel,
        row: Int,
        label: String,
        field: JComponent,
        button: JButton?
    ) {
        val labelConstraints = GridBagConstraints()
        labelConstraints.gridx = 0
        labelConstraints.gridy = row
        labelConstraints.insets = Insets(4, 4, 4, 4)
        labelConstraints.anchor = GridBagConstraints.WEST

        val fieldConstraints = GridBagConstraints()
        fieldConstraints.gridx = 1
        fieldConstraints.gridy = row
        fieldConstraints.weightx = 1.0
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL
        fieldConstraints.insets = Insets(4, 4, 4, 4)

        panel.add(JLabel(label), labelConstraints)
        panel.add(field, fieldConstraints)

        if (button != null) {
            val buttonConstraints = GridBagConstraints()
            buttonConstraints.gridx = 2
            buttonConstraints.gridy = row
            buttonConstraints.insets = Insets(4, 4, 4, 4)
            buttonConstraints.anchor = GridBagConstraints.EAST

            panel.add(button, buttonConstraints)
        }
    }

    private fun setupSqlTabs(project: Project, queryTabs: JTabbedPane) {
        isRestoringSqlTabs = true

        try {
            val savedTabs = loadSqlTabs(project).ifEmpty {
                listOf(defaultSql())
            }

            savedTabs.forEach { sql ->
                addSqlTab(
                    project = project,
                    queryTabs = queryTabs,
                    initialSql = sql,
                    selectNewTab = false
                )
            }

            queryTabs.addTab(plusTabTitle, JPanel())

            val selectedIndex = PropertiesComponent.getInstance(project)
                .getInt(selectedSqlTabKey, 0)
                .coerceIn(0, countSqlTabs(queryTabs) - 1)

            queryTabs.selectedIndex = selectedIndex
        } finally {
            isRestoringSqlTabs = false
        }

        queryTabs.addChangeListener {
            if (isAddingSqlTab || isRestoringSqlTabs) {
                return@addChangeListener
            }

            val selectedIndex = queryTabs.selectedIndex

            if (selectedIndex < 0) {
                return@addChangeListener
            }

            if (queryTabs.getTitleAt(selectedIndex) == plusTabTitle) {
                addSqlTab(
                    project = project,
                    queryTabs = queryTabs,
                    initialSql = defaultSql(),
                    selectNewTab = true
                )
            } else {
                saveSqlTabs(project, queryTabs)
            }
        }

        saveSqlTabs(project, queryTabs)
    }

    private fun addSqlTab(
        project: Project,
        queryTabs: JTabbedPane,
        initialSql: String,
        selectNewTab: Boolean
    ) {
        if (isAddingSqlTab) {
            return
        }

        isAddingSqlTab = true

        try {
            val editor = JTextArea(initialSql)
            editor.tabSize = 4
            editor.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = saveSqlTabs(project, queryTabs)

                override fun removeUpdate(e: DocumentEvent) = saveSqlTabs(project, queryTabs)

                override fun changedUpdate(e: DocumentEvent) = saveSqlTabs(project, queryTabs)
            })

            val panel = JPanel(BorderLayout())
            panel.putClientProperty(sqlEditorKey, editor)
            panel.add(JScrollPane(editor), BorderLayout.CENTER)

            val plusIndex = findPlusTabIndex(queryTabs)
            val insertIndex = if (plusIndex >= 0) plusIndex else queryTabs.tabCount
            val tabNumber = countSqlTabs(queryTabs) + 1

            queryTabs.insertTab("SQL $tabNumber", null, panel, null, insertIndex)

            if (selectNewTab) {
                queryTabs.selectedIndex = insertIndex
            }

            renumberSqlTabs(queryTabs)
        } finally {
            isAddingSqlTab = false
        }

        saveSqlTabs(project, queryTabs)
    }

    private fun removeActiveSqlTab(
        project: Project,
        queryTabs: JTabbedPane,
        logArea: JTextArea
    ) {
        val selectedIndex = queryTabs.selectedIndex

        if (selectedIndex < 0 || queryTabs.getTitleAt(selectedIndex) == plusTabTitle) {
            logArea.text = "Select SQL tab to delete"
            return
        }

        if (countSqlTabs(queryTabs) <= 1) {
            logArea.text = "At least one SQL tab is required"
            return
        }

        queryTabs.removeTabAt(selectedIndex)
        renumberSqlTabs(queryTabs)
        queryTabs.selectedIndex = selectedIndex.coerceAtMost(countSqlTabs(queryTabs) - 1)
        saveSqlTabs(project, queryTabs)
    }

    private fun findPlusTabIndex(queryTabs: JTabbedPane): Int {
        for (i in 0 until queryTabs.tabCount) {
            if (queryTabs.getTitleAt(i) == plusTabTitle) {
                return i
            }
        }

        return -1
    }

    private fun countSqlTabs(queryTabs: JTabbedPane): Int {
        var count = 0

        for (i in 0 until queryTabs.tabCount) {
            if (queryTabs.getTitleAt(i) != plusTabTitle) {
                count++
            }
        }

        return count
    }

    private fun renumberSqlTabs(queryTabs: JTabbedPane) {
        var sqlTabNumber = 1

        for (i in 0 until queryTabs.tabCount) {
            if (queryTabs.getTitleAt(i) != plusTabTitle) {
                queryTabs.setTitleAt(i, "SQL $sqlTabNumber")
                sqlTabNumber++
            }
        }
    }

    private fun getActiveSql(queryTabs: JTabbedPane): String {
        val selectedIndex = queryTabs.selectedIndex

        if (selectedIndex < 0 || queryTabs.getTitleAt(selectedIndex) == plusTabTitle) {
            return ""
        }

        val component = queryTabs.getComponentAt(selectedIndex) as? JComponent
            ?: return ""

        val editor = component.getClientProperty(sqlEditorKey) as? JTextArea
            ?: return ""

        return editor.text.trim()
    }

    private fun saveSqlTabs(project: Project, queryTabs: JTabbedPane) {
        if (isRestoringSqlTabs) {
            return
        }

        val properties = PropertiesComponent.getInstance(project)
        val tabs = mutableListOf<String>()

        for (i in 0 until queryTabs.tabCount) {
            if (queryTabs.getTitleAt(i) == plusTabTitle) {
                continue
            }

            val component = queryTabs.getComponentAt(i) as? JComponent
                ?: continue
            val editor = component.getClientProperty(sqlEditorKey) as? JTextArea
                ?: continue

            tabs.add(editor.text)
        }

        properties.setValue(sqlTabsCountKey, tabs.size, 1)

        tabs.forEachIndexed { index, sql ->
            properties.setValue(sqlTabTextKeyPrefix + index, encode(sql))
        }

        for (index in tabs.size until 50) {
            properties.unsetValue(sqlTabTextKeyPrefix + index)
        }

        val selectedIndex = queryTabs.selectedIndex
        if (selectedIndex >= 0 && selectedIndex < queryTabs.tabCount && queryTabs.getTitleAt(selectedIndex) != plusTabTitle) {
            properties.setValue(selectedSqlTabKey, selectedIndex, 0)
        }
    }

    private fun loadSqlTabs(project: Project): List<String> {
        val properties = PropertiesComponent.getInstance(project)
        val count = properties.getInt(sqlTabsCountKey, 0)

        if (count <= 0) {
            return emptyList()
        }

        return (0 until count)
            .mapNotNull { index ->
                properties.getValue(sqlTabTextKeyPrefix + index)?.let { decode(it) }
            }
    }

    private fun encode(value: String): String {
        return Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    private fun decode(value: String): String {
        return runCatching {
            String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        }.getOrDefault(value)
    }

    private fun defaultSql(): String {
        return "SELECT name FROM sqlite_master WHERE type = 'table';"
    }

    private fun listDevices(): List<DeviceItem> {
        val adb = findAdb()

        val result = runCommand(
            listOf(adb, "devices", "-l")
        )

        if (result.exitCode != 0) {
            throw RuntimeException(result.output)
        }

        val devices = mutableListOf<DeviceItem>()

        result.output
            .lineSequence()
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val parts = line.split(Regex("\\s+"))

                if (parts.size >= 2) {
                    val serial = parts[0]
                    val state = parts[1]

                    if (state == "device") {
                        val type = if (serial.startsWith("emulator-")) "emulator" else "device"

                        devices.add(
                            DeviceItem(
                                serial = serial,
                                type = type
                            )
                        )
                    }
                }
            }

        return devices
    }

    private fun getAppRootDirectory(
        deviceSerial: String,
        packageName: String
    ): String {
        val adb = findAdb()

        val result = runCommand(
            listOf(
                adb,
                "-s",
                deviceSerial,
                "shell",
                "run-as",
                packageName,
                "pwd"
            )
        )

        if (result.exitCode != 0) {
            throw RuntimeException(
                """
                Cannot get app root directory.

                adb output:
                ${result.output}

                Possible reasons:
                - wrong package name
                - app is not debuggable
                - device is not authorized
                """.trimIndent()
            )
        }

        return result.output.trim().ifBlank {
            "/data/user/0/$packageName"
        }
    }

    private fun showAppFileExplorer(
        deviceSerial: String,
        packageName: String,
        appRoot: String,
        initialPath: String,
        logArea: JTextArea,
        onSelected: (String) -> Unit
    ) {
        val dialog = JDialog()
        dialog.title = "Choose SQLite file"
        dialog.setSize(820, 560)
        dialog.setLocationRelativeTo(null)

        val currentPathField = JTextField(initialPath)
        val listModel = DefaultListModel<DeviceFileItem>()
        val fileList = JList(listModel)
        fileList.cellRenderer = DefaultListCellRenderer().also { renderer ->
            renderer.horizontalAlignment = JLabel.LEFT
        }

        val appRootButton = JButton("App root")
        val databasesButton = JButton("databases")
        val filesButton = JButton("files")
        val appFlutterButton = JButton("app_flutter")
        val cacheButton = JButton("cache")
        val sharedPrefsButton = JButton("shared_prefs")
        val noBackupButton = JButton("no_backup")

        val upButton = JButton("Up")
        val refreshButton = JButton("Refresh")
        val openButton = JButton("Open")
        val selectButton = JButton("Select file")
        val selectTypedPathButton = JButton("Use path")

        fun loadDirectory(path: String) {
            runInBackground(
                logArea = logArea,
                task = {
                    listAppFiles(
                        deviceSerial = deviceSerial,
                        packageName = packageName,
                        directoryPath = path
                    )
                },
                onSuccess = { files ->
                    currentPathField.text = path
                    listModel.clear()

                    files.forEach {
                        listModel.addElement(it)
                    }

                    logArea.text = formatVisibleFilesLog(
                        title = "Opened: $path",
                        files = files
                    )
                }
            )
        }

        fun openSelected() {
            val selected = fileList.selectedValue ?: return

            if (selected.isDirectory) {
                loadDirectory(selected.path)
            } else {
                onSelected(selected.path)
                dialog.dispose()
            }
        }

        appRootButton.addActionListener {
            loadDirectory(appRoot)
        }

        databasesButton.addActionListener {
            loadDirectory("$appRoot/databases")
        }

        filesButton.addActionListener {
            loadDirectory("$appRoot/files")
        }

        appFlutterButton.addActionListener {
            loadDirectory("$appRoot/app_flutter")
        }

        cacheButton.addActionListener {
            loadDirectory("$appRoot/cache")
        }

        sharedPrefsButton.addActionListener {
            loadDirectory("$appRoot/shared_prefs")
        }

        noBackupButton.addActionListener {
            loadDirectory("$appRoot/no_backup")
        }

        upButton.addActionListener {
            loadDirectory(getParentDir(currentPathField.text.trim()))
        }

        refreshButton.addActionListener {
            loadDirectory(currentPathField.text.trim())
        }

        openButton.addActionListener {
            openSelected()
        }

        selectButton.addActionListener {
            val selected = fileList.selectedValue ?: return@addActionListener

            if (selected.isDirectory) {
                logArea.text = "Selected item is directory, not file"
                return@addActionListener
            }

            onSelected(selected.path)
            dialog.dispose()
        }

        selectTypedPathButton.addActionListener {
            val typedPath = currentPathField.text.trim()

            if (typedPath.isBlank()) {
                logArea.text = "Path is empty"
                return@addActionListener
            }

            onSelected(typedPath)
            dialog.dispose()
        }

        fileList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    openSelected()
                }
            }
        })

        val pathPanel = JPanel(BorderLayout())
        pathPanel.add(JLabel("Path:"), BorderLayout.WEST)
        pathPanel.add(currentPathField, BorderLayout.CENTER)

        val quickPanel = JPanel()
        quickPanel.add(appRootButton)
        quickPanel.add(databasesButton)
        quickPanel.add(filesButton)
        quickPanel.add(appFlutterButton)
        quickPanel.add(cacheButton)
        quickPanel.add(sharedPrefsButton)
        quickPanel.add(noBackupButton)

        val actionPanel = JPanel()
        actionPanel.add(upButton)
        actionPanel.add(refreshButton)
        actionPanel.add(openButton)
        actionPanel.add(selectButton)
        actionPanel.add(selectTypedPathButton)

        val topPanel = JPanel(BorderLayout())
        topPanel.add(pathPanel, BorderLayout.NORTH)
        topPanel.add(quickPanel, BorderLayout.SOUTH)

        val root = JPanel(BorderLayout())
        root.add(topPanel, BorderLayout.NORTH)
        root.add(JScrollPane(fileList), BorderLayout.CENTER)
        root.add(actionPanel, BorderLayout.SOUTH)

        dialog.contentPane = root
        dialog.isVisible = true

        loadDirectory(initialPath)
    }

    private fun listAppFiles(
        deviceSerial: String,
        packageName: String,
        directoryPath: String
    ): List<DeviceFileItem> {
        val adb = findAdb()
        val safeDir = shellQuote(directoryPath)

        val command = """
            dir=$safeDir
            [ -d "${'$'}dir" ] || exit 1

            for path in "${'$'}dir"/* "${'$'}dir"/.*; do
                [ -e "${'$'}path" ] || continue

                name="${'$'}{path##*/}"
                [ "${'$'}name" = "." ] && continue
                [ "${'$'}name" = ".." ] && continue

                if [ -d "${'$'}path" ]; then
                    echo "D|${'$'}name"
                else
                    echo "F|${'$'}name"
                fi
            done
        """.trimIndent()

        val result = runCommand(
            listOf(
                adb,
                "-s",
                deviceSerial,
                "exec-out",
                "run-as",
                packageName,
                "sh",
                "-c",
                command
            )
        )

        if (result.exitCode != 0) {
            throw RuntimeException(
                """
                Cannot open directory:
                $directoryPath

                adb output:
                ${result.output}

                Possible reasons:
                - directory does not exist
                - app is not debuggable
                - wrong package name
                """.trimIndent()
            )
        }

        return result.output
            .lineSequence()
            .map { it.trim() }
            .filter { it.contains("|") }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 2)

                if (parts.size != 2) {
                    return@mapNotNull null
                }

                val type = parts[0]
                val name = parts[1]

                DeviceFileItem(
                    name = name,
                    path = joinDevicePath(directoryPath, name),
                    isDirectory = type == "D"
                )
            }
            .sortedWith(
                compareBy<DeviceFileItem> { !it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
            .toList()
    }

    private fun pullAppFileFromDevice(
        deviceSerial: String,
        packageName: String,
        deviceFilePath: String
    ): File {
        val tempDir = Files.createTempDirectory("phone-sqlite-").toFile()

        val fileName = deviceFilePath
            .substringAfterLast("/")
            .ifBlank { "device-file.db" }

        val localFile = File(tempDir, fileName)

        pullAppFileViaRunAs(
            deviceSerial = deviceSerial,
            packageName = packageName,
            remotePath = deviceFilePath,
            outputFile = localFile,
            required = true
        )

        pullAppFileViaRunAs(
            deviceSerial = deviceSerial,
            packageName = packageName,
            remotePath = "$deviceFilePath-wal",
            outputFile = File(tempDir, "$fileName-wal"),
            required = false
        )

        pullAppFileViaRunAs(
            deviceSerial = deviceSerial,
            packageName = packageName,
            remotePath = "$deviceFilePath-shm",
            outputFile = File(tempDir, "$fileName-shm"),
            required = false
        )

        return localFile
    }

    private fun pullAppFileViaRunAs(
        deviceSerial: String,
        packageName: String,
        remotePath: String,
        outputFile: File,
        required: Boolean
    ) {
        val adb = findAdb()

        val command = "cat ${shellQuote(remotePath)}"

        val process = ProcessBuilder(
            adb,
            "-s",
            deviceSerial,
            "exec-out",
            "run-as",
            packageName,
            "sh",
            "-c",
            command
        )
            .redirectOutput(outputFile)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        val exitCode = process.waitFor()
        val error = process.errorStream.bufferedReader().readText()

        if (exitCode != 0) {
            outputFile.delete()

            if (required) {
                val parentDirectory = getParentDir(remotePath)
                val visibleFiles = runCatching {
                    listAppFiles(
                        deviceSerial = deviceSerial,
                        packageName = packageName,
                        directoryPath = parentDirectory
                    )
                }.getOrNull()
                val visibleFilesText = if (visibleFiles == null) {
                    "Cannot list parent directory: $parentDirectory"
                } else {
                    formatVisibleFilesLog(
                        title = "Other files visible in: $parentDirectory",
                        files = visibleFiles
                    )
                }

                throw RuntimeException(
                    """
                    Cannot pull app file:
                    $remotePath

                    adb error:
                    $error

                    $visibleFilesText

                    Possible reasons:
                    - wrong package name
                    - app is not debuggable
                    - file does not exist
                    """.trimIndent()
                )
            }
        }
    }

    private fun formatVisibleFilesLog(
        title: String,
        files: List<DeviceFileItem>
    ): String {
        val fileLines = files
            .take(200)
            .joinToString(separator = "\n") { it.toString() }
            .ifBlank { "(empty)" }
        val moreCount = files.size - 200
        val moreLine = if (moreCount > 0) "\n...and $moreCount more" else ""

        return "$title\nItems: ${files.size}\n$fileLines$moreLine"
    }

    private fun executeSql(
        dbFile: File,
        sql: String
    ): SqlExecutionResult {
        val dataSource = SQLiteDataSource()
        dataSource.url = "jdbc:sqlite:${dbFile.absolutePath}"

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val hasResultSet = statement.execute(sql)

                if (hasResultSet) {
                    statement.resultSet.use { resultSet ->
                        val metaData = resultSet.metaData
                        val columnCount = metaData.columnCount

                        val columns = Vector<String>()
                        for (i in 1..columnCount) {
                            columns.add(metaData.getColumnName(i))
                        }

                        val rows = Vector<Vector<Any?>>()

                        while (resultSet.next()) {
                            val row = Vector<Any?>()

                            for (i in 1..columnCount) {
                                row.add(normalizeValue(resultSet.getObject(i)))
                            }

                            rows.add(row)
                        }

                        return SqlExecutionResult(
                            columns = columns,
                            rows = rows,
                            message = "Query executed. Rows: ${rows.size}"
                        )
                    }
                }

                val updateCount = statement.updateCount

                return SqlExecutionResult(
                    columns = Vector(),
                    rows = Vector(),
                    message = "Statement executed. Updated rows: $updateCount"
                )
            }
        }
    }

    private fun normalizeValue(value: Any?): Any? {
        return when (value) {
            is ByteArray -> "BLOB (${value.size} bytes)"
            else -> value
        }
    }

    private fun runCommand(args: List<String>): CommandResult {
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return CommandResult(
            exitCode = exitCode,
            output = output
        )
    }

    private fun findAdb(): String {
        val androidHome = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")

        if (androidHome != null) {
            val windowsAdb = File(androidHome, "platform-tools/adb.exe")
            if (windowsAdb.exists()) {
                return windowsAdb.absolutePath
            }

            val unixAdb = File(androidHome, "platform-tools/adb")
            if (unixAdb.exists()) {
                return unixAdb.absolutePath
            }
        }

        return "adb"
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

    private fun joinDevicePath(dir: String, name: String): String {
        if (dir == "/") {
            return "/$name"
        }

        if (dir.endsWith("/")) {
            return dir + name
        }

        return "$dir/$name"
    }

    private fun getParentDir(path: String): String {
        val cleanPath = path.trim().trimEnd('/')

        if (cleanPath.isBlank() || cleanPath == "/") {
            return "/"
        }

        if (!cleanPath.contains("/")) {
            return "/"
        }

        val parent = cleanPath.substringBeforeLast("/")

        return parent.ifBlank { "/" }
    }

    private fun getExplorerInitialDirectory(
        path: String,
        fallbackDirectory: String
    ): String {
        val cleanPath = path.trim()

        if (cleanPath.isBlank()) {
            return fallbackDirectory
        }

        val lastSegment = cleanPath.substringAfterLast("/")

        if (lastSegment.contains(".")) {
            return getParentDir(cleanPath)
        }

        return cleanPath
    }


    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private data class DeviceItem(
        val serial: String,
        val type: String
    ) {
        override fun toString(): String {
            return "$serial ($type)"
        }
    }

    private data class DeviceFileItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean
    ) {
        override fun toString(): String {
            val type = if (isDirectory) "[DIR] " else "[FILE]"
            return "$type $name"
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String
    )

    private data class SqlExecutionResult(
        val columns: Vector<String>,
        val rows: Vector<Vector<Any?>>,
        val message: String
    )
}
