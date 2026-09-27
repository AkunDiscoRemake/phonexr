package com.samrat.cardboardhands

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zone.ien.hig.CupertinoText
import zone.ien.hig.theme.CupertinoTheme
import kotlin.concurrent.thread

/**
 * PhoneXR account (Supabase): sign in, create an account, sign out. PhoneXR asks for it on start
 * ([EXTRA_REQUIRED]): until the user is signed in there is no way past this screen.
 */
class AccountActivity : ComponentActivity() {
    private val required get() = intent.getBooleanExtra(EXTRA_REQUIRED, false)

    private var user by mutableStateOf<Account.User?>(null)
    private var creating by mutableStateOf(false)
    private var email by mutableStateOf("")
    private var password by mutableStateOf("")
    private var name by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L10n.init(this)
        // Without an account, back leaves PhoneXR instead of slipping past the sign-in.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (required && Account.current(this@AccountActivity) == null) finishAffinity() else finish()
            }
        })
        user = Account.current(this)
        name = Settings.userName(this)
        setContent { PhoneXRTheme { Screen() } }
    }

    private fun submit() {
        if (busy) return
        busy = true
        error = null
        val (mail, pass, nick) = Triple(email.trim(), password, name.trim())
        thread {
            val result = if (creating) Account.signUp(this, mail, pass, nick.ifBlank { mail.substringBefore('@') })
            else Account.signIn(this, mail, pass)
            runOnUiThread {
                busy = false
                error = result
                user = Account.current(this)
                // Signed in on the start screen: straight on into PhoneXR.
                if (required && user != null) finish()
            }
        }
    }

    @Composable
    private fun Screen() {
        HigPage(
            title = if (required) "PhoneXR" else tr("Аккаунт"),
            subtitle = if (required) "Для входа в PhoneXR нужен аккаунт: друзья, звонки, магазин и настройки идут с вами на любой телефон." else null,
            onBack = if (required) null else ::finish
        ) {
            val current = user
            if (current != null) {
                HigSection(footer = "С аккаунтом вы видны друзьям в приложении «Звонки» в шлеме и можете звонить им персоной.") {
                    HigRow(current.name, current.email)
                }
                HigSection {
                    HigLink(tr("Выйти")) {
                        Account.signOut(this@AccountActivity)
                        Calls.stop()
                        user = null
                    }
                }
                return@HigPage
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CupertinoText(
                    if (creating) "Создайте аккаунт PhoneXR" else "Войдите в аккаунт PhoneXR",
                    fontSize = 22.sp, fontWeight = FontWeight.SemiBold
                )
                if (creating) Field("Имя", name, false) { name = it }
                Field("Почта", email, false, KeyboardType.Email) { email = it }
                Field("Пароль (от 6 символов)", password, true, KeyboardType.Password) { password = it }
                error?.let { CupertinoText(it, color = Color(0xFFFF453A)) }
                Box(
                    Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(Color(0xFF0A84FF))
                        .clickable(enabled = !busy && email.isNotBlank() && password.length >= 6) { submit() },
                    contentAlignment = Alignment.Center
                ) {
                    if (busy) HigSpinner()
                    else CupertinoText(if (creating) "Создать аккаунт" else tr("Войти"), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                CupertinoText(
                    if (creating) "Уже есть аккаунт? Войти" else "Нет аккаунта? Создать",
                    color = CupertinoTheme.colorScheme.accent,
                    modifier = Modifier.clickable { creating = !creating; error = null }.padding(vertical = 8.dp)
                )
            }
        }
    }

    @Composable
    private fun Field(hint: String, value: String, secret: Boolean, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
        val label = CupertinoTheme.colorScheme.label
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(CupertinoTheme.colorScheme.secondarySystemGroupedBackground).padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (value.isEmpty()) CupertinoText(hint, color = CupertinoTheme.colorScheme.secondaryLabel)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = label, fontSize = 17.sp),
                cursorBrush = SolidColor(Color(0xFF0A84FF)),
                keyboardOptions = KeyboardOptions(keyboardType = type),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    companion object {
        /** Started by PhoneXR on launch: the user must sign in to go on. */
        const val EXTRA_REQUIRED = "required"
    }
}
