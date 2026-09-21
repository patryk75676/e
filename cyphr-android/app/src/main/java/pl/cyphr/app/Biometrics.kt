package pl.cyphr.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Drugi skladnik logowania.
 *
 * Okno potwierdzenia rysuje system, nie aplikacja. Dzieki temu kazdy telefon
 * pokazuje swoje wlasne okno: czytnik pod ekranem podswietla miejsce dotyku,
 * telefon z czytnikiem z tylu albo w przycisku zasilania pokazuje swoja podpowiedz,
 * a urzadzenie z rozpoznawaniem twarzy od razu skanuje twarz.
 * Aplikacja nigdy nie pisze, gdzie jest czytnik, bo tego nie moze wiedziec.
 */
object Biometrics {

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Co telefon potrafi sprawdzic. */
    sealed interface State {
        /** Gotowe. [label] to nazwa w mianowniku, [instrumental] w narzedniku. */
        data class Ready(val label: String, val instrumental: String) : State
        /** Sprzet jest, ale nic nie zapisano. Mozna wyslac uzytkownika do ustawien. */
        object NotEnrolled : State
        /** Telefon nie ma ani biometrii, ani kodu ekranu blokady. */
        object None : State
        /** Czytnik chwilowo zajety albo niedostepny. */
        object Unavailable : State
    }

    fun state(context: Context): State {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val biometric = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                    BiometricManager.BIOMETRIC_SUCCESS
                if (biometric) namesFor(context) else State.Ready("kod ekranu blokady", "kodem ekranu blokady")
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> State.NotEnrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> State.None
            else -> State.Unavailable
        }
    }

    /**
     * Nazwa zalezy od tego, co telefon faktycznie ma. Nie zakladamy odcisku palca,
     * bo na czesci urzadzen jedyna metoda jest twarz albo teczowka.
     */
    private fun namesFor(context: Context): State.Ready {
        val pm = context.packageManager
        val finger = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        val face = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm.hasSystemFeature(PackageManager.FEATURE_FACE)
        val iris = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm.hasSystemFeature(PackageManager.FEATURE_IRIS)
        return when {
            finger && (face || iris) -> State.Ready("odcisk palca lub twarz", "odciskiem palca lub twarzą")
            finger -> State.Ready("odcisk palca", "odciskiem palca")
            face -> State.Ready("skan twarzy", "skanem twarzy")
            iris -> State.Ready("skan tęczówki", "skanem tęczówki")
            else -> State.Ready("dane biometryczne", "danymi biometrycznymi")
        }
    }

    fun available(context: Context): Boolean = state(context) is State.Ready

    /** Ekran systemowy, na ktorym ustawia sie blokade albo dopisuje odcisk czy twarz. */
    fun enrollIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                AUTHENTICATORS,
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    fun prompt(
        activity: FragmentActivity,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit = {},
    ) {
        when (val state = state(activity)) {
            is State.Ready -> Unit
            State.NotEnrolled -> {
                onFailure("Telefon nie ma jeszcze zapisanej blokady ekranu. Ustaw ją i wróć.")
                return
            }
            State.None -> {
                onFailure("To urządzenie nie obsługuje potwierdzania tożsamości.")
                return
            }
            State.Unavailable -> {
                onFailure("Czytnik jest chwilowo niedostępny. Spróbuj za moment.")
                return
            }
        }
        // Tresc i wyglad okna dobiera system pod konkretny telefon.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("CYPHR")
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        onFailure(null)
                    } else {
                        onFailure(message.toString())
                    }
                }
            },
        ).authenticate(info)
    }
}
