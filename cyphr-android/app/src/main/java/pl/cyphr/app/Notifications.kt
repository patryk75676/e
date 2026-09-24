package pl.cyphr.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Powiadomienia czatu pracujacego w tle. Nigdy nie pokazuja tresci rozmowy —
 * powiadomienie widac na ekranie blokady, a rozmowy bywaja prywatne.
 */
object Notifications {
    private const val WORK = "praca"
    private const val DONE = "odpowiedzi"
    const val WORKING_ID = 1
    private const val READY_ID = 2
    private const val APPROVAL_ID = 3

    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            // Nazwy kanalow w jezyku aplikacji — ponowne utworzenie tylko je podmienia.
            NotificationChannel(WORK, tr("Odpowiedź w toku", "Reply in progress"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr(
                    "Widać, gdy model kończy odpowiedź, a aplikacja jest w tle.",
                    "Shown while the model finishes a reply and the app is in the background.",
                )
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(DONE, tr("Gotowe odpowiedzi", "Finished replies"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = tr(
                    "Odpowiedź gotowa albo model prosi o zgodę na polecenie.",
                    "A reply is ready or the model asks for permission to run a command.",
                )
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        // Z powiadomienia nie ma biezacego zadania — NEW_TASK wymaga system, SINGLE_TOP
        // wraca do otwartego ekranu zamiast tworzyc drugi.
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Stale powiadomienie uslugi, poki model odpowiada. */
    fun working(context: Context, name: String): Notification {
        channels(context)
        return NotificationCompat.Builder(context, WORK)
            .setSmallIcon(R.drawable.ghost)
            .setContentTitle(tr("$name odpowiada…", "$name is replying…"))
            .setContentText(tr("Możesz wyjść z aplikacji — odpowiedź zapisze się w rozmowie.", "You can leave the app — the reply will be saved in the chat."))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .build()
    }

    fun replyReady(context: Context, name: String) =
        post(context, READY_ID, tr("$name odpowiedział", "$name replied"), tr("Dotknij, żeby przeczytać odpowiedź.", "Tap to read the reply."))

    fun approvalNeeded(context: Context, name: String) =
        post(
            context,
            APPROVAL_ID,
            tr("$name prosi o zgodę na polecenie", "$name asks for permission to run a command"),
            tr("Otwórz CYPHR, żeby zezwolić albo odmówić.", "Open CYPHR to allow or deny."),
        )

    fun failed(context: Context, message: String) =
        post(context, READY_ID, tr("Nie udało się dokończyć odpowiedzi", "Couldn't finish the reply"), message)

    fun cancelApproval(context: Context) = NotificationManagerCompat.from(context).cancel(APPROVAL_ID)

    /** Po wejsciu do aplikacji stare „odpowiedz gotowa” nie maja juz sensu. */
    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(READY_ID)
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        channels(context)
        val notification = NotificationCompat.Builder(context, DONE)
            .setSmallIcon(R.drawable.ghost)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openApp(context))
            .build()
        // Bez zgody na powiadomienia (Android 13+) po prostu ich nie ma — czat dziala dalej.
        try { NotificationManagerCompat.from(context).notify(id, notification) } catch (e: SecurityException) { }
    }
}
