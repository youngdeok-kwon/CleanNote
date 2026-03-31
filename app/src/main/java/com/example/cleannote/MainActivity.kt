package com.example.cleannote

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cleannote.ui.theme.CleanNoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CleanNoteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        url = "http://139.150.82.48:8023/",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

// 웹과 앱 사이의 통신을 담당하는 클래스
class WebAppInterface(private val activity: ComponentActivity) {
    @JavascriptInterface
    fun processPayment(amount: String) {
        // 웹에서 보낸 금액을 받아 처리하는 부분
        activity.runOnUiThread {
            // 실제 결제 SDK 호출 로직이 여기에 들어갑니다.
            Toast.makeText(activity, "결제 요청 금액: ${amount}원", Toast.LENGTH_LONG).show()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(url: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        canGoBack = view?.canGoBack() ?: false
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                
                // JavaScript Interface 등록
                // 웹에서 window.Android.processPayment(금액) 으로 호출 가능
                addJavascriptInterface(WebAppInterface(ctx as ComponentActivity), "Android")

                loadUrl(url)
                webView = this
            }
        },
        update = {
            webView = it
        }
    )
}
