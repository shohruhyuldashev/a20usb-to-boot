package com.example.a20usbboot

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val logs = mutableStateListOf<String>()
    
    private suspend fun appendLog(msg: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            logs.add(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val rootSession = RootSession { msg -> appendLog(msg) }
        val deviceChecker = DeviceChecker(rootSession) { msg -> appendLog(msg) }
        val isoManager = IsoManager(this, rootSession) { msg -> appendLog(msg) }
        val usbManager = UsbGadgetManager(rootSession, deviceChecker) { msg -> appendLog(msg) }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(rootSession, deviceChecker, isoManager, usbManager, logs)
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    rootSession: RootSession,
    deviceChecker: DeviceChecker,
    isoManager: IsoManager,
    usbManager: UsbGadgetManager,
    logs: List<String>
) {
    val coroutineScope = rememberCoroutineScope()
    var isSupported by remember { mutableStateOf(false) }
    var selectedIso by remember { mutableStateOf<Uri?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isProcessing = true
        val hasRoot = rootSession.start()
        if (hasRoot) {
            isSupported = deviceChecker.checkCompatibility()
        }
        isProcessing = false
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedIso = uri
        }
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("A20 USB Boot", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Text("Status: ${if (isProcessing) "WORKING..." else if (isSupported) "READY" else "NOT SUPPORTED"}")
        Text("ISO: ${selectedIso?.lastPathSegment ?: "Not selected"}")
        
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { filePicker.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = !isProcessing
        ) {
            Text("Select ISO File")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                selectedIso?.let { uri ->
                    coroutineScope.launch {
                        isProcessing = true
                        val prepared = isoManager.prepareIso(uri)
                        if (prepared) {
                            usbManager.setupBootableUsb(isoManager.targetPath)
                        }
                        isProcessing = false
                    }
                }
            },
            enabled = isSupported && selectedIso != null && !isProcessing
        ) {
            Text("PREPARE USB")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                coroutineScope.launch {
                    isProcessing = true
                    isoManager.cleanupStorage()
                    usbManager.restoreNormal()
                    isProcessing = false
                }
            },
            enabled = isSupported && !isProcessing
        ) {
            Text("RESTORE STORAGE AND NORMAL USB")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Log:", style = MaterialTheme.typography.titleMedium)
        
        val listState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) {
                listState.animateScrollToItem(logs.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(logs) { log ->
                Text(log, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
