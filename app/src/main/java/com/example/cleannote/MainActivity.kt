package com.example.cleannote

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var paymentLauncher: ActivityResultLauncher<Intent>
    private var mainWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 결제 앱 결과 처리를 위한 런처 등록
        paymentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                // 결제 앱에서 반환한 결과(예: 승인번호 등)를 웹뷰로 전달
                val resultJson = "{ \"status\": \"success\", \"data\": \"${data?.toUri(0)}\" }"
                mainWebView?.evaluateJavascript("window.onPaymentResult($resultJson)", null)
                Toast.makeText(this, "결제 성공", Toast.LENGTH_SHORT).show()
            } else {
                mainWebView?.evaluateJavascript("window.onPaymentResult({ \"status\": \"fail\" })", null)
                Toast.makeText(this, "결제 취소/실패", Toast.LENGTH_SHORT).show()
            }
        }

        enableEdgeToEdge()
        setContent {
            CleanNoteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        url = "http://139.150.82.48:8023/",
                        modifier = Modifier.padding(innerPadding),
                        onWebViewCreated = { mainWebView = it }
                    )
                }
            }
        }
    }

    // 결제 앱(ACheck-AppCall) 호출 함수
    fun startPaymentApp(amount: String, datano: String, paymentType: String, installment: String) {
        try {
            val intent = Intent().apply {
                // TODO: 문서에 명시된 정확한 Action 또는 Package/Class 명칭을 입력하세요.
                // 예: action = "com.acheck.appcall.PAYMENT" 
                // 또는 setClassName("com.acheck.appcall", "com.acheck.appcall.PaymentActivity")
                
                // 문서 규격에 따른 Extra 데이터 세팅 (예시)
                putExtra("REQ_TYPE", if (paymentType == "8") "CARD" else "CASH")
                putExtra("TOTAL_AMT", amount.toLongOrNull() ?: 0L)
                putExtra("ORDER_NO", datano)
                putExtra("INSTALLMENT", installment)
                
                // 기타 ACheck 문서에서 요구하는 필드들을 추가하세요.
                putExtra("TERMINAL_ID", "테스트ID") 
            }
            paymentLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "결제 앱을 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}

class WebAppInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun processPayment(amount: String, datano: String, paymentType: String, installment: String) {
        activity.runOnUiThread {
            // Intent 호출 실행
            activity.startPaymentApp(amount, datano, paymentType, installment)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(url: String, modifier: Modifier = Modifier, onWebViewCreated: (WebView) -> Unit) {
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
                addJavascriptInterface(WebAppInterface(ctx as MainActivity), "Android")
                loadUrl(url)
                onWebViewCreated(this)
                webView = this
            }
        },
        update = { webView = it }
    )
}
