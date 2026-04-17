package com.example.cleannote

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cleannote.ui.theme.CleanNoteTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// 디버그 모드 설정 (true: 파라미터 확인 팝업 표시, false: 즉시 실행)
private const val IS_DEBUG = true

class MainActivity : ComponentActivity() {
    private lateinit var paymentLauncher: ActivityResultLauncher<Intent>
    private var mainWebView: WebView? = null
    private var isDeviceInitialized = false

    // 설정 관련 상수
    private val PREFS_NAME = "cleanpos_config"
    private val KEY_HOST = "server_host"
    private val KEY_PORT = "server_port"
    private val KEY_PROVIDER = "payment_provider"

    private val DEFAULT_HOST = "139.150.82.48"
    private val DEFAULT_PORT = "8023"
    private val DEFAULT_PROVIDER = "KICC"

    private fun sendLogToWeb(tag: String, message: String) {
        val fullLog = "[$tag] $message"
        Log.d("CleanNoteLog", fullLog)
        runOnUiThread {
            val escapedLog = fullLog.replace("'", "\\'").replace("\n", " ")
            mainWebView?.evaluateJavascript("window.onPaymentLog('$escapedLog')", null)
        }
    }

    // 결과 팝업 (인쇄 선택 기능 포함)
    private fun showPaymentResultDialog(
        title: String,
        message: String,
        showPrintOption: Boolean = false,
        onPrintAction: (() -> Unit)? = null
    ) {
        runOnUiThread {
            val builder = AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)

            if (showPrintOption && onPrintAction != null) {
                builder.setPositiveButton("인쇄") { dialog, _ ->
                    onPrintAction()
                    dialog.dismiss()
                }
                builder.setNegativeButton("닫기") { dialog, _ ->
                    dialog.dismiss()
                }
            } else {
                builder.setPositiveButton("확인") { dialog, _ ->
                    dialog.dismiss()
                }
            }
            builder.show()
        }
    }

    private fun showIntentDebugDialog(title: String, intent: Intent, onConfirm: () -> Unit) {
        val sb = StringBuilder()
        val extras = intent.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                sb.append("$key: ${extras.get(key)}\n")
            }
        }
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("전송 파라미터:\n\n$sb")
                .setPositiveButton("실행") { _, _ -> onConfirm() }
                .setNegativeButton("취소", null)
                .setCancelable(false) // 사용자가 수동으로 닫기 전까지 유지
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        paymentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val resultCode = (data.getStringExtra("RESULT_CODE") ?: data.extras?.get("RESULT_CODE")?.toString() ?: "").trim()
                val resultMsg = (data.getStringExtra("RESULT_MSG") ?: data.extras?.get("RESULT_MSG")?.toString() ?: "").trim()
                val tranType = (data.getStringExtra("TRAN_TYPE") ?: data.extras?.get("TRAN_TYPE")?.toString() ?: "").trim()

                if (tranType == "F1") {
                    if (resultCode == "0000") {
                        isDeviceInitialized = true
                        sendLogToWeb("INIT_SUCCESS", "장치 초기화 성공")
                    } else {
                        sendLogToWeb("INIT_FAILED", "초기화 실패: $resultMsg ($resultCode)")
                    }
                    return@registerForActivityResult
                }

                if (tranType == "F5") {
                    sendLogToWeb("PRINT_RESULT", "Code: $resultCode, Msg: $resultMsg")
                    return@registerForActivityResult
                }

                val approvalNum = (data.getStringExtra("APPROVAL_NUM") ?: "").trim()
                val approvalDate = (data.getStringExtra("APPROVAL_DATE") ?: "").trim()
                val cardName = (data.getStringExtra("CARD_NAME") ?: "").trim()
                val cardNum = (data.getStringExtra("CARD_NUM") ?: "").trim()
                val totalAmount = (data.getStringExtra("TOTAL_AMOUNT") ?: "").trim()

                val isSuccess = resultCode == "0000"
                val status = if (isSuccess) "success" else "fail"
                val resultJson = """{ "status": "$status", "resultCode": "$resultCode", "resultMsg": "$resultMsg", "approvalNum": "$approvalNum", "approvalDate": "$approvalDate", "cardName": "$cardName", "cardNum": "$cardNum", "totalAmount": "$totalAmount" }"""

                mainWebView?.evaluateJavascript("window.onPaymentResult($resultJson)", null)

                if (isSuccess) {
                    if (tranType == "D4" || tranType == "A9") {
                        mainWebView?.evaluateJavascript("window.onCardCancelResult('0000', '취소성공')", null)
                        showPaymentResultDialog("취소 성공", "결제 취소가 완료되었습니다.")
                    } else if (tranType == "B1") {
                        showPaymentResultDialog(
                            title = "현금영수증 발행 성공",
                            message = "금액: ${totalAmount}원\n승인번호: $approvalNum\n\n영수증을 인쇄하시겠습니까?",
                            showPrintOption = true,
                            onPrintAction = {
                                printKiccReceipt(totalAmount, "현금영수증", "", approvalNum, approvalDate)
                            }
                        )
                    } else {
                        mainWebView?.evaluateJavascript("onCardApproveResult('$approvalNum', '$approvalDate', '$cardName')", null)
                        showPaymentResultDialog(
                            title = "결제 성공",
                            message = "금액: ${totalAmount}원\n승인번호: $approvalNum\n카드: $cardName\n\n영수증을 인쇄하시겠습니까?",
                            showPrintOption = true,
                            onPrintAction = {
                                printKiccReceipt(totalAmount, cardName, cardNum, approvalNum, approvalDate)
                            }
                        )
                    }
                } else {
                    val errorTitle = when(tranType) {
                        "D4", "A9" -> "취소 실패"
                        "B1" -> "현금영수증 실패"
                        else -> "결제 실패"
                    }
                    showPaymentResultDialog(errorTitle, "사유: $resultMsg\n오류코드: $resultCode")
                }
            } else {
                showPaymentResultDialog("작업 취소", "요청이 취소되었습니다.")
            }
        }

        checkStoragePermission()

        enableEdgeToEdge()
        setContent {
            CleanNoteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        url = getServerUrl(),
                        modifier = Modifier.padding(innerPadding),
                        onWebViewCreated = {
                            mainWebView = it
                            if (!isDeviceInitialized) initPaymentDevice()
                        }
                    )
                }
            }
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val notGranted = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1001)
            }
        }
    }

    fun printKiccReceipt(amount: String, cardName: String, cardNum: String, approvalNum: String, approvalDate: String) {
        thread {
            try {
                val CRLF = "\r\n"
                val sb = StringBuilder().apply {
                    append("C").append(CRLF)
                    if (cardName == "현금영수증") append("T220      [ 현 금 영 수 증 ]").append(CRLF)
                    else append("T220      [ 영 수 증 ]").append(CRLF)
                    append("L24").append(CRLF)
                    append("T110금    액 : ${amount}원").append(CRLF)
                    if (cardName != "현금영수증") {
                        append("T110카 드 명 : ${cardName}").append(CRLF)
                        append("T110카드번호 : ${cardNum}").append(CRLF)
                    }
                    append("T110승인번호 : ${approvalNum}").append(CRLF)
                    append("T110승인일시 : ${approvalDate}").append(CRLF)
                    append("T110--------------------------------").append(CRLF)
                    append("T110  세차노트를 이용해 주셔서 감사합니다.").append(CRLF)
                    append("L120").append(CRLF)
                    append("PCF").append(CRLF)
                }
                val file = File(Environment.getExternalStorageDirectory(), "print_kicc.txt")
                if (file.exists()) file.delete()
                file.writeBytes(sb.toString().toByteArray(charset("EUC-KR")))
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
                        putExtra("TRAN_NO", createDefaultDataNo())
                        putExtra("TRAN_TYPE", "F5")
                        putExtra("PRINT_DATA", file.absolutePath)
                        putExtra("PACKAGE_NAME", packageName)
                    }
                    paymentLauncher.launch(intent)
                }
            } catch (e: Exception) { sendLogToWeb("ERROR", "Print Failed: ${e.message}") }
        }
    }
    fun __printKiccReceipt(amount: String, cardName: String, cardNum: String, approvalNum: String, approvalDate: String) {
        thread {
            try {
                val sb = StringBuilder()
                if (cardName == "현금영수증") sb.append("      [ 현 금 영 수 증 ]\n\n")
                else sb.append("      [ 영 수 증 ]\n\n")
                sb.append("금    액 : ${amount}원\n")
                if (cardName != "현금영수증") {
                    sb.append("카 드 명 : ${cardName}\n")
                    sb.append("카드번호 : ${cardNum}\n")
                }
                sb.append("승인번호 : ${approvalNum}\n")
                sb.append("승인일시 : ${approvalDate}\n")
                sb.append("--------------------------------\n")
                sb.append("  세차노트를 이용해 주셔서 감사합니다.\n\n\n\n")

                val directory = Environment.getExternalStorageDirectory()
                val file = File(directory, "print.txt")
                if (file.exists()) file.delete()

                // [수정] 인쇄용 텍스트를 EUC-KR 인코딩으로 저장하여 한글 깨짐 방지
                file.writeBytes(sb.toString().toByteArray(charset("EUC-KR")))

                val filePath = file.absolutePath
                sendLogToWeb("FILE_PREPARED", "Path: $filePath")

                runOnUiThread {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
                        putExtra("TRAN_NO", createDefaultDataNo())
                        putExtra("TRAN_TYPE", "F5")
                        putExtra("PRINT_DATA", filePath)
                        putExtra("PACKAGE_NAME", packageName)
                    }
                    showIntentDebugDialog("KICC 인쇄 요청", intent) {
                        paymentLauncher.launch(intent)
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown Error"
                sendLogToWeb("ERROR", "Print Preparation Failed: $errorMsg")
                runOnUiThread {
                    showPaymentResultDialog(
                        "인쇄 준비 실패",
                        "파일 생성 중 오류가 발생했습니다.\n\n사유: $errorMsg\n\n휴대폰 설정 > 앱 > CleanNote > 권한에서 '저장공간'을 허용했는지 확인해 주세요."
                    )
                }
            }
        }
    }

    fun startPaymentApp(amount: String, datano: String, paymentType: String, installment: String) {
        val provider = getPaymentProvider()
        if (provider == "KICC") {
            when (paymentType) {
            "D4" -> startKiccCancel(amount, datano, installment)
            "A9" -> startKiccSerialCancel(amount, datano, installment)
            "B1" -> startKiccCashReceipt(amount, datano)
            else -> startKiccPayment(amount, datano, paymentType, installment)
            }
        }
    }

    private fun startKiccPayment(amount: String, datano: String, paymentType: String, installment: String) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
                putExtra("TRAN_NO", if (datano.isEmpty()) createDefaultDataNo() else datano)
                putExtra("TRAN_TYPE", when(paymentType) { "8" -> "D1"; "0", "0R" -> "D2"; else -> paymentType })
                val kiccInstall = if (installment.length == 1) "0$installment" else if (installment.isEmpty()) "00" else installment
                putExtra("TOTAL_AMOUNT", amount)
                putExtra("INSTALLMENT", kiccInstall)
                putExtra("PACKAGE_NAME", packageName)
            }
            if (IS_DEBUG) showIntentDebugDialog("KICC 승인", intent) { paymentLauncher.launch(intent) }
            else paymentLauncher.launch(intent)
        } catch (e: Exception) { sendLogToWeb("ERROR", "Payment Failed: ${e.message}") }
    }

    private fun startKiccCashReceipt(amount: String, datano: String) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
                putExtra("TRAN_NO", if (datano.isEmpty()) createDefaultDataNo() else datano)
                putExtra("TRAN_TYPE", "B1")
                putExtra("TOTAL_AMOUNT", amount)
                putExtra("INSTALLMENT", "00")
                putExtra("PACKAGE_NAME", packageName)
            }
            if (IS_DEBUG) showIntentDebugDialog("KICC 현금영수증", intent) { paymentLauncher.launch(intent) }
            else paymentLauncher.launch(intent)
        } catch (e: Exception) { sendLogToWeb("ERROR", "CashReceipt Failed: ${e.message}") }
    }

    private fun startKiccCancel(amount: String, datano: String, installment: String) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
                putExtra("TRAN_NO", if (datano.isEmpty()) createDefaultDataNo() else datano)
                putExtra("TRAN_TYPE", "D4")
                putExtra("TOTAL_AMOUNT", amount)
                val parts = installment.split("|")
                if (parts.size >= 3) {
                    putExtra("INSTALLMENT", "00")
                    putExtra("APPROVAL_NO", parts[1].trim())
                    val date = parts[2].trim().let { if(it.length==8) it.substring(2) else it }
                    putExtra("APPROVAL_DATE", date)
                }
            }
            if (IS_DEBUG) showIntentDebugDialog("KICC 취소", intent) { paymentLauncher.launch(intent) }
            else paymentLauncher.launch(intent)
        } catch (e: Exception) { sendLogToWeb("ERROR", "Cancel Failed: ${e.message}") }
    }

    private fun startKiccSerialCancel(amount: String, datano: String, installment: String) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
                putExtra("TRAN_NO", if (datano.isEmpty()) createDefaultDataNo() else datano)
                putExtra("TRAN_TYPE", "A9")
                putExtra("TOTAL_AMOUNT", amount)
                val parts = installment.split("|")
                if (parts.size >= 3) {
                    putExtra("INSTALLMENT", "00")
                    putExtra("APPROVAL_NO", parts[1].trim())
                    val date = parts[2].trim().let { if(it.length==8) it.substring(2) else it }
                    putExtra("APPROVAL_DATE", date)
                }
                putExtra("OPTION_FIELD", "A9#$datano")
                putExtra("PACKAGE_NAME", packageName)
            }
            if (IS_DEBUG) showIntentDebugDialog("KICC 일련번호 취소", intent) { paymentLauncher.launch(intent) }
            else paymentLauncher.launch(intent)
        } catch (e: Exception) { sendLogToWeb("ERROR", "SerialCancel Failed: ${e.message}") }
    }

    fun initPaymentDevice() {
        if (getPaymentProvider() != "KICC") return
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
                putExtra("TRAN_NO", createDefaultDataNo())
                putExtra("TRAN_TYPE", "F1")
                putExtra("PACKAGE_NAME", packageName)
            }
            paymentLauncher.launch(intent)
        } catch (e: Exception) { sendLogToWeb("ERROR", "Init Failed: ${e.message}") }
    }

    private fun createDefaultDataNo() = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
    private fun getServerUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val port = prefs.getString(KEY_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        return "http://$host:$port/"
    }
    private fun getPaymentProvider(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PROVIDER, DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
    }
    fun saveServerConfig(host: String, port: String, provider: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_HOST, host); putString(KEY_PORT, port); putString(KEY_PROVIDER, provider); apply()
        }
        Toast.makeText(this, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
    }
}

class WebAppInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun processPayment(amount: String, datano: String, paymentType: String, installment: String) {
        activity.runOnUiThread { activity.startPaymentApp(amount, datano, paymentType, installment) }
    }
    @JavascriptInterface
    fun setServerConfig(host: String, port: String, provider: String) {
        activity.runOnUiThread { activity.saveServerConfig(host, port, provider) }
    }
    @JavascriptInterface
    fun initDevice() { activity.runOnUiThread { activity.initPaymentDevice() } }
    @JavascriptInterface
    fun printReceipt(amount: String, cardName: String, cardNum: String, approvalNum: String, approvalDate: String) {
        activity.printKiccReceipt(amount, cardName, cardNum, approvalNum, approvalDate)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(url: String, modifier: Modifier = Modifier, onWebViewCreated: (WebView) -> Unit) {
    var webViewInstance: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    BackHandler(enabled = canGoBack) { webViewInstance?.goBack() }
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
                webViewInstance = this
            }
        },
        update = { webViewInstance = it }
    )
}
