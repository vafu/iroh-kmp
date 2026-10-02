package io.github.vafu.iroh.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vafu.iroh.Endpoint
import io.github.vafu.iroh.EndpointOptions
import io.github.vafu.iroh.NativeEndpointFactory
import io.github.vafu.iroh.initializeAndroidIroh
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeAndroidIroh(this)
        setContent { MaterialTheme { EndpointSample() } }
    }
}

@Composable
private fun EndpointSample() {
    var endpoint by remember { mutableStateOf<Endpoint?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val cleanupScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching {
            NativeEndpointFactory.bind(
                EndpointOptions(alpns = listOf(Endpoint.Alpn("iroh-kmp/sample/1"))),
            )
        }.onSuccess { endpoint = it }
            .onFailure { error = it.message ?: it::class.simpleName }
    }
    DisposableEffect(Unit) {
        onDispose { endpoint?.let { cleanupScope.launch { it.close() } } }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("iroh-kmp", style = MaterialTheme.typography.headlineMedium)
            when {
                error != null -> Text("Endpoint failed: $error", color = MaterialTheme.colorScheme.error)
                endpoint == null -> {
                    CircularProgressIndicator()
                    Text("Binding a native Iroh endpoint…")
                }
                else -> {
                    Text("Endpoint ready")
                    Text(endpoint!!.id.value, style = MaterialTheme.typography.bodySmall)
                    Text("Addresses: ${endpoint!!.addr().addrs.size}")
                }
            }
        }
    }
}
