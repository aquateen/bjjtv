package io.github.aquateen.bjjtv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Shows the site's own sign-in page. Once the site has signed you in it stores a session
 * token in the page's local storage; this screen waits for that token, saves it, and moves on.
 *
 * To avoid typing with the remote, the screen also runs a small web page on the home network.
 * Open the address shown on the TV on a phone, type the email and password there, and the app
 * fills them into the site's sign-in form. They are passed straight to the form and never stored.
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var hint: TextView
    private lateinit var intro: LinearLayout
    private lateinit var qr: ImageView
    private lateinit var phoneText: TextView
    private lateinit var remoteButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var staleToken: String? = null
    private var clearedStale = false
    private var done = false

    private var server: ServerSocket? = null
    private val pairCode = (1000..9999).random().toString()
    private var wrongCodes = 0

    private val poll = object : Runnable {
        override fun run() {
            if (done) return
            check()
            handler.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        staleToken = intent.getStringExtra("stale")

        // Layer 1: the site's sign-in page, used directly with the remote or filled in from the phone.
        val webLayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hint = TextView(this).apply {
            text = "Sign in below with the remote. The app continues on its own once you're signed in.  ·  Press Back for the phone option."
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(16, 24, 48))
            textSize = 15f
            setPadding(dp(24), dp(10), dp(24), dp(10))
        }
        webLayer.addView(hint)
        web = WebView(this)
        webLayer.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        // Layer 2 (on top at first): explains the two ways to sign in.
        intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 14, 28))
            setPadding(dp(56), dp(32), dp(56), dp(24))
            isClickable = true
        }
        intro.addView(TextView(this).apply {
            text = "Sign in to BJJ Fanatics"
            setTextColor(Color.WHITE)
            textSize = 28f
        })
        intro.addView(TextView(this).apply {
            text = "Typing an email and password with a TV remote is slow, so you can use your phone instead."
            setTextColor(Color.LTGRAY)
            textSize = 16f
            setPadding(0, dp(6), 0, dp(20))
        })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        qr = ImageView(this).apply { setBackgroundColor(Color.WHITE) }
        row.addView(qr, LinearLayout.LayoutParams(dp(210), dp(210)))
        phoneText = TextView(this).apply {
            text = "Preparing phone sign-in…"
            setTextColor(Color.WHITE)
            textSize = 16f
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(32), 0, 0, 0)
        }
        row.addView(phoneText, LinearLayout.LayoutParams(0, -2, 1f))
        intro.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        remoteButton = Button(this).apply {
            text = "Use the remote instead"
            setOnClickListener { showWeb() }
        }
        intro.addView(remoteButton, LinearLayout.LayoutParams(-2, -2))

        val root = FrameLayout(this)
        root.addView(webLayer)
        root.addView(intro)
        setContentView(root)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            // Marketing, chat and pop-up scripts are not needed to sign in: drop their requests.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host ?: return null
                return if (BLOCKED.any { host == it || host.endsWith(".$it") }) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else null
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(HIDE_BANNERS, null)
            }
        }
        web.loadUrl("https://bjjfanatics.com/account/login")
        remoteButton.requestFocus()
        handler.postDelayed(poll, 1500)
        startPhonePage()
    }

    private fun showWeb() {
        intro.visibility = View.GONE
        web.requestFocus()
    }

    private fun showIntro() {
        intro.visibility = View.VISIBLE
        remoteButton.requestFocus()
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
        return bmp
    }

    private fun check() {
        val host = Uri.parse(web.url ?: "").host ?: return
        if (host != "bjjfanatics.com" && host != "www.bjjfanatics.com") return

        // A token the server already rejected: remove it once so the site issues a new one.
        if (staleToken != null && !clearedStale) {
            clearedStale = true
            web.evaluateJavascript("localStorage.removeItem('CapacitorStorage.token')") { web.reload() }
            return
        }

        web.evaluateJavascript("localStorage.getItem('CapacitorStorage.token')") { raw ->
            val token = try {
                (JSONTokener(raw ?: "null").nextValue() as? String)?.trim()?.trim('"')
            } catch (e: Exception) {
                null
            }
            if (!done && !token.isNullOrBlank() && token != staleToken) {
                done = true
                Prefs.setToken(this, token)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    // ---- Sign in from a phone -------------------------------------------------------------

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun phoneUnavailable() {
        qr.visibility = View.GONE
        phoneText.text = "Phone sign-in isn't available right now (the TV needs to be on Wi-Fi).\n" +
            "Choose \"Use the remote instead\" to sign in on this screen."
    }

    private fun startPhonePage() {
        val ip = localIp()
        if (ip == null) {
            phoneUnavailable()
            return
        }
        thread(isDaemon = true) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                server = s
                val address = "http://$ip:$PORT"
                val code = qrBitmap("$address/?c=$pairCode", 420)
                runOnUiThread {
                    qr.setImageBitmap(code)
                    phoneText.text =
                        "1.  Connect your phone to the same Wi-Fi as this TV and scan the code with its camera.\n" +
                        "2.  A sign-in page opens. This TV itself is showing your phone that page, which is why " +
                        "its address is a number ($address) and not a website name. Your phone may label it \"not secure\" for the same reason.\n" +
                        "3.  Enter your BJJ Fanatics email and password there. The TV types them into the " +
                        "BJJ Fanatics sign-in page for you. They stay on your home network and are not saved.\n\n" +
                        "No camera? Open $address in your phone's browser and enter code $pairCode."
                }
                while (!s.isClosed) {
                    val client = s.accept()
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        // A broken request from the phone is not worth surfacing.
                    } finally {
                        client.close()
                    }
                }
            } catch (e: Exception) {
                // Port unavailable or screen closed: the remote sign-in still works.
                if (server == null) runOnUiThread { phoneUnavailable() }
            }
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 4096) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input)
        var length = 0
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            if (line.startsWith("content-length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        // The QR code carries the pairing code (?c=1234) so it doesn't have to be typed.
        var knownCode = Uri.parse(requestLine.split(' ').getOrElse(1) { "/" }).getQueryParameter("c") == pairCode
        var message = ""
        if (requestLine.startsWith("POST") && length in 1..8192) {
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n <= 0) break
                read += n
            }
            val form = String(body, 0, read, Charsets.UTF_8).split('&').associate {
                val k = it.substringBefore('=')
                val v = it.substringAfter('=', "")
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
            knownCode = form["code"]?.trim() == pairCode
            message = when {
                wrongCodes >= 5 -> "Too many wrong codes. Reopen the sign-in screen on the TV for a new code."
                form["code"]?.trim() != pairCode -> {
                    wrongCodes++
                    "That code doesn't match the one on the TV."
                }
                form["email"].isNullOrBlank() -> "Enter your email."
                else -> {
                    val email = form["email"]!!.trim()
                    val password = form["password"] ?: ""
                    runOnUiThread { fillForm(email, password) }
                    "Sent. Check the TV: it should be signing in now."
                }
            }
        }

        val html = phonePage(message, knownCode).toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.write(html)
        out.flush()
    }

    private fun phonePage(message: String, knownCode: Boolean): String {
        val note = if (message.isBlank()) "" else "<p class=m>${message.replace("<", "&lt;")}</p>"
        val codeField = if (knownCode) {
            "<input type=\"hidden\" name=\"code\" value=\"$pairCode\">"
        } else {
            "<label>Code shown on the TV</label><input name=\"code\" inputmode=\"numeric\" autocomplete=\"off\" required>"
        }
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>BJJ TV sign-in</title>
<style>body{font-family:sans-serif;background:#101830;color:#fff;padding:24px;max-width:420px;margin:auto}
input,button{width:100%;box-sizing:border-box;font-size:18px;padding:12px;margin:6px 0 14px;border-radius:8px;border:0}
button{background:#e12d2d;color:#fff;font-weight:bold}.m{background:#28468f;padding:12px;border-radius:8px}
small{color:#aab}</style></head><body><h2>BJJ TV sign-in</h2>$note
<p>This page comes from the BJJ TV app on your TV, over your home Wi-Fi. What you enter is sent to the TV, which types it into the BJJ Fanatics sign-in page.</p>
<form method="post" action="/">
$codeField
<label>BJJ Fanatics email</label><input name="email" type="email" autocomplete="username" required>
<label>Password</label><input name="password" type="password" autocomplete="current-password">
<button type="submit">Send to TV</button></form>
<small>Your details are not saved by the app. Prefer not to use this page? Choose "Use the remote instead" on the TV.</small>
</body></html>"""
    }

    /** Types the details into the sign-in form currently shown in the WebView and submits it. */
    private fun fillForm(email: String, password: String) {
        showWeb()
        val js = """
            (function(e, p) {
              function set(el, v) {
                Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, v);
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
              }
              function visible(el) { return el.offsetParent !== null; }
              var pw = Array.prototype.slice.call(document.querySelectorAll('input[type=password]')).filter(visible)[0];
              var scope = (pw && pw.form) || document;
              var em = Array.prototype.slice.call(scope.querySelectorAll(
                'input[type=email], input[name*="email" i], input[autocomplete=username]')).filter(visible)[0];
              if (!em) return 'no-form';
              set(em, e);
              if (pw && p) set(pw, p);
              var f = em.form;
              if (!f) return 'filled';
              if (f.requestSubmit) f.requestSubmit(); else f.submit();
              return 'submitted';
            })(${JSONObject.quote(email)}, ${JSONObject.quote(password)})
        """.trimIndent()
        web.evaluateJavascript(js) { result ->
            val msg = when {
                result?.contains("no-form") == true -> "Couldn't find the sign-in form on this page. Open the sign-in page on the TV, then send again."
                result?.contains("filled") == true -> "Details filled in. Press the sign-in button with the remote."
                else -> "Signing in…"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // From the site's page, Back steps back through it and then returns to the sign-in options.
        if (keyCode == KeyEvent.KEYCODE_BACK && intro.visibility != View.VISIBLE) {
            if (web.canGoBack()) web.goBack() else showIntro()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        done = true
        handler.removeCallbacksAndMessages(null)
        try {
            server?.close()
        } catch (e: Exception) {
        }
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PORT = 8765

        /** Third-party services the store loads for marketing, chat, reviews and tracking. */
        private val BLOCKED = listOf(
            "klaviyo.com", "rebuyengine.com", "swymrelay.com", "swym.it", "smile.io", "luckyorange.com",
            "luckyorange.net", "userway.org", "shoplift.ai", "tabarn.app", "aggle.net", "stamped.io",
            "privy.com", "justuno.com", "attn.tv", "postscript.io", "omnisend.com", "hotjar.com",
            "clarity.ms", "googletagmanager.com", "google-analytics.com", "doubleclick.net",
            "facebook.net", "tiktok.com", "bing.com", "getuploadkit.com"
        )

        /** Hides cookie banners and newsletter overlays that the request blocking doesn't catch. */
        private const val HIDE_BANNERS = """
            (function() {
              var s = document.createElement('style');
              s.textContent = '#shopify-pc__banner, .shopify-pc__banner__dialog, .cc-window, .cc-banner,' +
                ' #onetrust-banner-sdk, [id*="cookie" i][class*="banner" i], [class*="cookie-banner" i],' +
                ' [class*="cookie-consent" i], .klaviyo-form, [class*="needsclick"][role="dialog"],' +
                ' .announcement-bar { display: none !important; }';
              document.head.appendChild(s);
            })()
        """
    }
}
