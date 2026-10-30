package ru.liftzip.mail

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import jakarta.mail.AuthenticationFailedException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class GoogleMailAuthException(message: String): IllegalStateException(message)

/** Google Play services owns refresh credentials. No client secret or refresh token is stored here. */
object GoogleMailAuth {
    const val SCOPE="https://mail.google.com/"
    const val MARKER="google-authorization"
    private val locks=ConcurrentHashMap<String,Any>()
    fun request(email: String)=AuthorizationRequest.builder().setAccount(Account(email,"com.google"))
        .setRequestedScopes(listOf(Scope(SCOPE))).build()
    fun token(context: Context,email: String): String=synchronized(locks.computeIfAbsent(email){Any()}) {
        try {
            val result=Tasks.await(Identity.getAuthorizationClient(context).authorize(request(email)),30,TimeUnit.SECONDS)
            require(!result.hasResolution()){"Для $email требуется повторный вход через Google. Откройте настройки этого ящика."}
            require(result.grantedScopes.any{it.scopeUri==SCOPE}){"Google не разрешил доступ к почте. Повторите вход в настройках ящика."}
            result.accessToken?.takeIf{it.isNotBlank()}?:error("Google не вернул доступ к почте. Повторите вход.")
        }catch(e: InterruptedException){Thread.currentThread().interrupt();throw e}catch(e: Exception){throw GoogleMailAuthException(problem(e,email))}
    }
    fun <T> withCredential(context: Context,a: MailAccount,password: String,connect: (String)->T): T {
        if(a.authKind!=AuthKind.GOOGLE)return connect(password)
        AuthRules.validateGoogle(a)?.let{error(it)}
        val first=token(context,a.email)
        return try{connect(first)}catch(e: AuthenticationFailedException){
            Tasks.await(Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(first).build()),15,TimeUnit.SECONDS)
            connect(token(context,a.email))
        }
    }
    fun disconnect(context: Context,email: String) {
        Tasks.await(Identity.getAuthorizationClient(context).revokeAccess(RevokeAccessRequest.builder().setAccount(Account(email,"com.google")).build()),20,TimeUnit.SECONDS)
    }
    fun problem(error: Throwable,email: String=""): String {
        val api=generateSequence(error){it.cause}.filterIsInstance<ApiException>().firstOrNull()
        return when(api?.statusCode){
            10->"Вход Google для этой сборки ещё не настроен. Пока можно использовать пароль приложения."
            16->"Вход Google отменён."
            else->if(error is IllegalArgumentException || error is IllegalStateException)error.message.orEmpty() else
                "Не удалось получить доступ Google${if(email.isNotBlank())" для $email" else ""}. Проверьте интернет и повторите вход в настройках ящика."
        }
    }
}
