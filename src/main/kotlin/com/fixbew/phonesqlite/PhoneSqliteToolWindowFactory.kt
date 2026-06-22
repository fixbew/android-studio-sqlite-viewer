package com.fixbew.phonesqlite

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
import java.util.Vector
import javax.swing.*
import javax.swing.table.DefaultTableModel

class PhoneSqliteToolWindowFactory : ToolWindowFactory {
    private var isAddingSqlTab = false
    private val plusTabTitle = "+"
    private val sqlEditorKey = "sqlEditor"

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val deviceCombo = JComboBox<DeviceItem>()
        val refreshDevicesButton = JButton("Refresh devices")

        val packageField = JTextField("com.example.app")

        val filePathField = JTextField("databases/app.db")
        val browseAppFilesButton = JButton("Browse App Files")

        val startButton = JButton("Start")

        val queryTabs = JTabbedPane()
        val resultTableModel = DefaultTableModel()
        val resultTable = JTable(resultTableModel)

        val logArea = JTextArea(5, 30)
        logArea.isEditable = false

        val topPanel = JPanel(GridBagLayout())

        addRow(
            panel = topPanel,
            row = 0,
            label = "Device:",
            field = deviceCombo,
            button = refreshDevicesButton
        )

        addRow(
            panel = topPanel,
            row = 1,
            label = "Package:",
            field = packageField,
            button = null
        )

        addRow(
            panel = topPanel,
            row = 2,
            label = "File:",
            field = filePathField,
            button = browseAppFilesButton
        )

        val startPanel = JPanel(BorderLayout())
        startPanel.add(startButton, BorderLayout.EAST)

        val sqlAndResultSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            queryTabs,
            JScrollPane(resultTable)
        )
        sqlAndResultSplit.dividerLocation = 260

        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(sqlAndResultSplit, BorderLayout.CENTER)
        mainPanel.add(startPanel, BorderLayout.SOUTH)

        val rootSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            mainPanel,
            JScrollPane(logArea)
        )
        rootSplit.dividerLocation = 600

        setupSqlTabs(queryTabs)

        refreshDevicesButton.addActionListener {
            runInBackground(
                logArea = logArea,
                task = {
                    listDevices()
                },
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
        logArea.text = "Select device first"
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
            showAppFileExplorer(
                deviceSerial = device.serial,
                packageName = packageName,
                appRoot = appRoot,
                initialPath = filePathField.text.trim().ifBlank { appRoot },
                logArea = logArea
            ) { selectedPath ->
                filePathField.text = selectedPath
            }
        }
    )
}

startButton.addActionListener {
    val device = deviceCombo.selectedItem as? DeviceItem
    val packageName = packageField.text.trim()
    val deviceFilePath = filePathField.text.trim()
    val sql = getActiveSql(queryTabs)

    if (device == null) {
        logArea.text = "Select device first"
        return@addActionListener
    }

    if (packageName.isBlank()) {
        logArea.text = "Package name is empty"
        return@addActionListener
    }

    if (deviceFilePath.isBlank()) {
        logArea.text = "Select file first"
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
}startButton.addActionListener {
    val device = deviceCombo.selectedItem as? DeviceItem
    val packageName = packageField.text.trim()
    val deviceFilePath = filePathField.text.trim()
    val sql = getActiveSql(queryTabs)

    if (device == null) {
        logArea.text = "Select device first"
        return@addActionListener
    }

    if (packageName.isBlank()) {
        logArea.text = "Package name is empty"
        return@addActionListener
    }

    if (deviceFilePath.isBlank()) {
        logArea.text = "Select file first"
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

    private fun setupSqlTabs(queryTabs: JTabbedPane) {
    queryTabs.addTab(plusTabTitle, JPanel())

    addSqlTab(queryTabs)

    queryTabs.addChangeListener {
        if (isAddingSqlTab) {
            return@addChangeListener
        }

        val selectedIndex = queryTabs.selectedIndex

        if (selectedIndex < 0) {
            return@addChangeListener
        }

        val selectedTitle = queryTabs.getTitleAt(selectedIndex)

        if (selectedTitle == plusTabTitle) {
            addSqlTab(queryTabs)
        }
    }
}

    private fun addSqlTab(queryTabs: JTabbedPane) {
    if (isAddingSqlTab) {
        return
    }

    isAddingSqlTab = true

    try {
        val editor = JTextArea()
        editor.text = "SELECT name FROM sqlite_master WHERE type = 'table';"
        editor.tabSize = 4

        val panel = JPanel(BorderLayout())
        panel.putClientProperty(sqlEditorKey, editor)
        panel.add(JScrollPane(editor), BorderLayout.CENTER)

        val plusIndex = findPlusTabIndex(queryTabs)
        val insertIndex = if (plusIndex >= 0) {
            plusIndex
        } else {
            queryTabs.tabCount
        }

        val tabNumber = countSqlTabs(queryTabs) + 1

        queryTabs.insertTab(
            "SQL $tabNumber",
            null,
            panel,
            null,
            insertIndex
        )

        queryTabs.selectedIndex = insertIndex
    } finally {
        isAddingSqlTab = false
    }
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
        if (queryTabs.getTitleAt(i).startsWith("SQL ")) {
            count++
        }
    }

    return count
}

    private fun getActiveSql(queryTabs: JTabbedPane): String {
        val selectedIndex = queryTabs.selectedIndex

        if (selectedIndex < 0) {
            return ""
        }

        if (queryTabs.getTitleAt(selectedIndex) == plusTabTitle) {
            return ""
        }

        val component = queryTabs.getComponentAt(selectedIndex) as? JComponent
            ?: return ""

        val editor = component.getClientProperty(sqlEditorKey) as? JTextArea
            ?: return ""

        return editor.text.trim()
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
                        val type = if (serial.startsWith("emulator-")) {
                            "emulator"
                        } else {
                            "usb"
                        }

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

    private fun listDatabaseFiles(
        deviceSerial: String,
        packageName: String
    ): List<String> {
        val adb = findAdb()

        val result = runCommand(
            listOf(
                adb,
                "-s",
                deviceSerial,
                "shell",
                "run-as",
                packageName,
                "sh",
                "-c",
                "ls databases"
            )
        )

        if (result.exitCode != 0) {
            throw RuntimeException(
                """
                Cannot list databases.
                
                adb output:
                ${result.output}
                
                Possible reasons:
                - wrong package name
                - app is not debuggable
                - app has no databases folder
                - device is not authorized
                """.trimIndent()
            )
        }

        return result.output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { !it.endsWith("-wal") }
            .filter { !it.endsWith("-shm") }
            .filter { !it.endsWith("-journal") }
            .sorted()
            .toList()
    }

    private fun pullFileFromDevice(
    deviceSerial: String,
    packageName: String,
    deviceFilePath: String
): File {
    val tempDir = Files.createTempDirectory("phone-sqlite-").toFile()
    val fileName = File(deviceFilePath).name.ifBlank { "device-file.db" }
    val localFile = File(tempDir, fileName)

    pullFileViaRunAs(
        deviceSerial = deviceSerial,
        packageName = packageName,
        remotePath = deviceFilePath,
        outputFile = localFile,
        required = true
    )

    // Если это SQLite WAL-база, рядом могут лежать .db-wal и .db-shm.
    // Копируем их тоже, если существуют.
    pullFileViaRunAs(
        deviceSerial = deviceSerial,
        packageName = packageName,
        remotePath = "$deviceFilePath-wal",
        outputFile = File(tempDir, "$fileName-wal"),
        required = false
    )

    pullFileViaRunAs(
        deviceSerial = deviceSerial,
        packageName = packageName,
        remotePath = "$deviceFilePath-shm",
        outputFile = File(tempDir, "$fileName-shm"),
        required = false
    )

    return localFile
}

    private fun pullFileViaRunAs(
        deviceSerial: String,
        packageName: String,
        remotePath: String,
        outputFile: File,
        required: Boolean
    ) {
        val adb = findAdb()

        val process = ProcessBuilder(
            adb,
            "-s",
            deviceSerial,
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
                    - wrong package name
                    - wrong database name
                    - app is not debuggable
                    - database file does not exist
                    """.trimIndent()
                )
            }
        }
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
                } else {
                    val updateCount = statement.updateCount

                    return SqlExecutionResult(
                        columns = Vector(),
                        rows = Vector(),
                        message = "Statement executed. Updated rows: $updateCount"
                    )
                }
            }
        }
    }

    private fun showDeviceFileChooser(
    deviceSerial: String,
    packageName: String,
    initialPath: String,
    logArea: JTextArea,
    onSelected: (String) -> Unit
) {
    val dialog = JDialog()
    dialog.title = "Choose file on device"
    dialog.setSize(600, 500)
    dialog.setLocationRelativeTo(null)

    val currentPathField = JTextField(getParentDir(initialPath))
    val listModel = DefaultListModel<DeviceFileItem>()
    val fileList = JList(listModel)

    val openButton = JButton("Open")
    val selectButton = JButton("Select")
    val upButton = JButton("Up")
    val refreshButton = JButton("Refresh")

    fun loadDirectory(path: String) {
        runInBackground(
            logArea = logArea,
            task = {
                listDeviceFiles(
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

                logArea.text = "Files loaded: ${files.size}"
            }
        )
    }

    openButton.addActionListener {
        val selected = fileList.selectedValue ?: return@addActionListener

        if (selected.isDirectory) {
            loadDirectory(selected.path)
        } else {
            onSelected(selected.path)
            dialog.dispose()
        }
    }

    selectButton.addActionListener {
        val selected = fileList.selectedValue ?: return@addActionListener

        if (!selected.isDirectory) {
            onSelected(selected.path)
            dialog.dispose()
        }
    }

    upButton.addActionListener {
        loadDirectory(getParentDir(currentPathField.text.trim()))
    }

    refreshButton.addActionListener {
        loadDirectory(currentPathField.text.trim())
    }

    fileList.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseClicked(e: java.awt.event.MouseEvent) {
            if (e.clickCount == 2) {
                openButton.doClick()
            }
        }
    })

    val topPanel = JPanel(BorderLayout())
    topPanel.add(JLabel("Path:"), BorderLayout.WEST)
    topPanel.add(currentPathField, BorderLayout.CENTER)

    val buttonsPanel = JPanel()
    buttonsPanel.add(upButton)
    buttonsPanel.add(refreshButton)
    buttonsPanel.add(openButton)
    buttonsPanel.add(selectButton)

    val root = JPanel(BorderLayout())
    root.add(topPanel, BorderLayout.NORTH)
    root.add(JScrollPane(fileList), BorderLayout.CENTER)
    root.add(buttonsPanel, BorderLayout.SOUTH)

    dialog.contentPane = root
    dialog.isVisible = true

    loadDirectory(currentPathField.text.trim())
}

private fun listDeviceFiles(
    deviceSerial: String,
    packageName: String,
    directoryPath: String
): List<DeviceFileItem> {
    val adb = findAdb()

    val safeDir = shellQuote(directoryPath)

    val command = """
    cd $safeDir && for f in * .*; do
        [ "${'$'}f" = "." ] && continue
        [ "${'$'}f" = ".." ] && continue

        if [ -d "${'$'}f" ]; then
            echo "D|${'$'}f"
        else
            echo "F|${'$'}f"
        fi
    done
""".trimIndent()

    val result = runCommand(
        listOf(
            adb,
            "-s",
            deviceSerial,
            "shell",
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
            Cannot list files.
            
            Path:
            $directoryPath
            
            adb output:
            ${result.output}
            
            Possible reasons:
            - wrong package name
            - app is not debuggable
            - directory does not exist
            - no access to this directory
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

            val fullPath = joinDevicePath(directoryPath, name)

            DeviceFileItem(
                name = name,
                path = fullPath,
                isDirectory = type == "D"
            )
        }
        .sortedWith(
            compareBy<DeviceFileItem> { !it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
        .toList()
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

private fun showAppFileExplorer(
    deviceSerial: String,
    packageName: String,
    appRoot: String,
    initialPath: String,
    logArea: JTextArea,
    onSelected: (String) -> Unit
) {
    val dialog = JDialog()
    dialog.title = "App File Explorer"
    dialog.setSize(800, 560)
    dialog.setLocationRelativeTo(null)

    val currentPathField = JTextField(initialPath)
    val listModel = DefaultListModel<DeviceFileItem>()
    val fileList = JList(listModel)

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

                logArea.text = "Opened: $path\nItems: ${files.size}"
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

private fun listAppFiles(
    deviceSerial: String,
    packageName: String,
    directoryPath: String
): List<DeviceFileItem> {
    val adb = findAdb()
    val safeDir = shellQuote(directoryPath)

    val command = """
        cd $safeDir 2>/dev/null || exit 1

        for f in * .*; do
            [ "${'$'}f" = "." ] && continue
            [ "${'$'}f" = ".." ] && continue
            [ ! -e "${'$'}f" ] && continue

            if [ -d "${'$'}f" ]; then
                echo "D|${'$'}f"
            else
                echo "F|${'$'}f"
            fi
        done
    """.trimIndent()

    val result = runCommand(
        listOf(
            adb,
            "-s",
            deviceSerial,
            "shell",
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

    val process = ProcessBuilder(
        adb,
        "-s",
        deviceSerial,
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
                Cannot pull app file:
                $remotePath
                
                adb error:
                $error
                
                Possible reasons:
                - wrong package name
                - app is not debuggable
                - file does not exist
                """.trimIndent()
            )
        }
    }
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
        return if (isDirectory) {
            "[DIR] $name"
        } else {
            "      $name"
        }
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