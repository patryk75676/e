package pl.cyphr.app

/**
 * Kiedy ostatnio korzystal z aplikacji ktos, kto potwierdzil tozsamosc. Czas lezy
 * w szyfrowanym schowku, wiec przetrwa zamkniecie aplikacji — w wybranym oknie
 * (domyslnie 24 h) nie trzeba potwierdzac sie przy kazdym uruchomieniu.
 * Nowe logowanie i wejscie na inne konto pytaja zawsze, niezaleznie od tego zegara.
 */
object LockClock {
    private const val KEY = "last_active"

    /**
     * Zapisuje „teraz”. Wolane wylacznie przy odblokowanej aplikacji — gdyby zapisywac
     * takze przy zablokowanej, zamkniecie i ponowne otwarcie odnawialoby okno bez odcisku.
     */
    fun touch(now: Long = System.currentTimeMillis()) = SecureStore.put(KEY, now.toString())

    fun lastActive(): Long? = SecureStore.get(KEY)?.toLongOrNull()

    fun forget() = SecureStore.put(KEY, null)

    /** Czy trzeba znowu potwierdzic tozsamosc — przy starcie i po powrocie z tla. */
    fun due(): Boolean = due(System.currentTimeMillis(), lastActive(), Prefs.appLock, Prefs.lockAfterSeconds)

    /**
     * Czysta logika [due]. Brak wpisu albo czas z przyszlosci (cofniety zegar) — pytamy,
     * bo nie wiadomo, ile minelo.
     */
    fun due(now: Long, lastActive: Long?, lockOn: Boolean, afterSeconds: Int): Boolean {
        if (!lockOn) return false
        if (lastActive == null || lastActive > now) return true
        return now - lastActive >= afterSeconds * 1000L
    }
}
