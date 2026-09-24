package pl.cyphr.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Rozmowy i odpowiedzi modelu zyja poza ekranem. Wczesniej odpowiedz liczyla sie
 * w korutynie ekranu, wiec wyjscie z aplikacji na dluzej, blokada odciskiem albo
 * odtworzenie ekranu urywaly ja w polowie. Teraz praca idzie w zakresie calej aplikacji,
 * [ChatService] trzyma proces przy zyciu, a wynik trafia na dysk i na ekran, jesli jest
 * otwarty. Zgoda na polecenie modelu czeka na uzytkownika — model nie ma drogi na skroty.
 *
 * Stan zmienia sie wylacznie na glownym watku. Dysk ma jeden wlasny watek, wiec zapisy
 * i odczyty ida po kolei — odczyt widzi wszystko, co zapisano przed nim.
 */
object ChatEngine {
    /** Tyle najdluzej czeka zgoda na polecenie, gdy nikt nie wraca do aplikacji. */
    private const val APPROVAL_TIMEOUT_MS = 10 * 60 * 1000L

    /** Odmowy serwera obrazow, ktore maja wlasny komunikat dla uzytkownika. */
    private val IMAGE_ERRORS_SHOWN = setOf("image_limit", "image_refused", "image_busy")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Jeden watek dysku — kolejne wersje rozmowy trafiaja na dysk w kolejnosci. */
    private val disk = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private lateinit var app: Context

    private val _chats = MutableStateFlow(listOf(Chat()))
    val chats: StateFlow<List<Chat>> = _chats

    private val _activeId = MutableStateFlow(_chats.value.first().id)
    val activeId: StateFlow<String> = _activeId

    /** Rozmowy, w ktorych model wlasnie odpowiada. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

    /** Rozmowy, w ktorych wlasnie powstaje obraz. */
    private val _drawing = MutableStateFlow<Set<String>>(emptySet())
    val drawing: StateFlow<Set<String>> = _drawing

    /** Rzeczywiste zuzycie ostatniej wymiany: wejscie do wyjscia. */
    private val _lastTokens = MutableStateFlow<Pair<Int, Int>?>(null)
    val lastTokens: StateFlow<Pair<Int, Int>?> = _lastTokens

    /** Dzienny limit obrazow konta na ekranie. Null — serwer nie tworzy obrazow albo jeszcze nie wiadomo. */
    private val _images = MutableStateFlow<ImageQuota?>(null)
    val images: StateFlow<ImageQuota?> = _images

    /** Polecenie, o ktore prosi model w rozmowie [chat], czekajace na zgode uzytkownika. */
    class Approval(
        val chatId: String,
        val chat: String,
        val command: String,
        internal val answer: CompletableDeferred<Boolean>,
    )

    private val _approval = MutableStateFlow<Approval?>(null)
    val approval: StateFlow<Approval?> = _approval
    private val approvals = Mutex()

    sealed interface Event {
        /** Komunikat dla ekranu konta [uid], np. blad odpowiedzi. */
        class Message(val uid: Long, val text: String) : Event
        /** Swieze dane konta po odpowiedzi — saldo sie zmienilo. */
        class Account(val user: User) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    /** Czy ekran aplikacji jest widoczny. Gdy nie — o wyniku mowi powiadomienie. */
    @Volatile var visible = false

    /** Konto, ktorego rozmowy sa w pamieci. Dla niego pamiec jest zrodlem prawdy, dysk — kopia. */
    private var owner: Long? = null

    /** Wczytywanie rozmow konta razem ze zmianami, ktore przyszly w jego trakcie. */
    private class Loading(val uid: Long) {
        val early = ArrayList<Pair<String, (Chat) -> Chat>>()
    }

    private var loading: Loading? = null

    /** Dokad otworzyc aplikacje, gdy rozmowy konta jeszcze sie wczytuja. */
    private var landing: Landing? = null

    /** Odpowiedzi w toku: rozmowa -> konto, ktore pytalo, i praca. */
    private val jobs = HashMap<String, Pair<Long, Job>>()

    fun init(context: Context) {
        if (!::app.isInitialized) {
            app = context.applicationContext
            scope.launch(disk) { Attachments.clearStaged(app) }
        }
    }

    /**
     * Rozmowy konta [userId] na ekran. Tego samego konta nie wczytujemy od nowa — w pamieci
     * moze byc odpowiedz, ktora jeszcze trwa. Rozmowy poprzedniego konta znikaja od razu,
     * a nie dopiero po wczytaniu nowych.
     */
    fun open(userId: Long) {
        if (userId == owner || loading?.uid == userId) return
        // Przerwane wczytywanie innego konta: zmiany z tego czasu ida prosto na dysk.
        loading?.let { old ->
            loading = null
            old.early.forEach { (id, block) -> update(old.uid, id, block) }
        }
        owner = null
        val blank = Chat()
        _chats.value = listOf(blank)
        _activeId.value = blank.id
        _lastTokens.value = null
        _images.value = null
        val mine = Loading(userId)
        loading = mine
        scope.launch {
            val list = withContext(disk) {
                Chats.loadAll(app, userId).also { loaded ->
                    // Pliki, do ktorych nie prowadzi juz zadna wiadomosc (np. po przycieciu rozmowy).
                    try { Attachments.collect(app, userId, loaded) } catch (_: Exception) {}
                }
            }
            // W miedzyczasie zazadano innego konta — to wczytanie jest juz nieaktualne.
            if (loading !== mine) return@launch
            loading = null
            owner = userId
            _chats.value = list.ifEmpty { listOf(Chat()) }
            _activeId.value = _chats.value.first().id
            landing?.let { apply(it) }
            landing = null
            // Zmiany z czasu wczytywania, np. odpowiedz, ktora wlasnie doszla.
            mine.early.forEach { (id, block) -> update(userId, id, block) }
            refreshImages(userId)
        }
    }

    /** Po powrocie do aplikacji: limit obrazow mogl sie odnowic, gdy aplikacja czekala w tle. */
    fun refreshImages() {
        owner?.let { if (loading == null) refreshImages(it) }
    }

    /** Sprawdza w tle, czy serwer tworzy obrazy i ile zostalo na dzis. */
    fun refreshImages(uid: Long) {
        val session = Api.token() ?: return
        scope.launch {
            val quota = try { Api.imageQuota(auth = session) } catch (_: Exception) { return@launch }
            if (uid == owner) _images.value = quota
        }
    }

    fun select(id: String) {
        if (_chats.value.any { it.id == id }) _activeId.value = id
    }

    /**
     * Po powrocie do aplikacji: konkretna rozmowa albo nowa, pusta. Gdy rozmowy konta
     * jeszcze sie wczytuja, wybor czeka na nie.
     */
    fun land(target: Landing?) {
        if (target == null) return
        if (owner != null && loading == null) apply(target) else landing = target
    }

    private fun apply(target: Landing) {
        when (target) {
            is Landing.At -> select(target.chatId)
            Landing.Fresh -> fresh()
        }
    }

    /**
     * Nowa, pusta rozmowa na wierzchu. Na dysk trafia dopiero z pierwsza wiadomoscia —
     * inaczej lista zapelnialaby sie pustymi rozmowami. Pusta juz jest — wracamy do niej.
     */
    private fun fresh() {
        val list = _chats.value
        list.firstOrNull { it.messages.isEmpty() && it.id !in _busy.value }?.let {
            _activeId.value = it.id
            return
        }
        val blank = Chat()
        _chats.value = listOf(blank) + list
        _activeId.value = blank.id
    }

    private fun save(uid: Long, chat: Chat) {
        scope.launch(disk) { Chats.save(app, uid, chat) }
    }

    /**
     * Podmienia rozmowe, liczac od jej aktualnej postaci. Rozmowa konta, ktorego nie ma
     * na ekranie (np. odpowiedz doszla po przelaczeniu konta), zmienia sie na dysku.
     * Rozmowy juz nie ma — niczego nie wskrzeszamy.
     */
    private fun update(uid: Long, id: String, block: (Chat) -> Chat) {
        val now = System.currentTimeMillis()
        val pending = loading
        when {
            uid == owner -> {
                val (list, next) = _chats.value.updated(id, now, block) ?: return
                _chats.value = list
                save(uid, next)
            }
            // Rozmowy tego konta jeszcze sie wczytuja — zmiana dojdzie zaraz po nich.
            pending != null && pending.uid == uid -> pending.early += id to block
            else -> scope.launch(disk) {
                Chats.load(app, uid, id)?.let { Chats.save(app, uid, block(it).copy(updatedAt = now)) }
            }
        }
    }

    fun newChat(uid: Long) {
        if (uid != owner) return
        fresh()
    }

    /** Nowa nazwa nie przesuwa rozmowy na gore listy — to nie jest nowa wiadomosc. */
    fun rename(uid: Long, id: String, title: String) {
        if (uid != owner) return
        _chats.value = _chats.value.map { c ->
            if (c.id == id) c.copy(title = title).also { save(uid, it) } else c
        }
    }

    fun deleteChat(uid: Long, id: String) {
        if (uid != owner) return
        // Odpowiedz do usunietej rozmowy nie ma gdzie trafic, a liczylaby sie z salda.
        jobs[id]?.second?.cancel()
        val gone = _chats.value.firstOrNull { it.id == id }
        scope.launch(disk) {
            Chats.delete(app, uid, id)
            gone?.let { c -> Attachments.delete(app, c.messages.flatMap { it.attachments }) }
        }
        // Zawsze zostaje przynajmniej jedna rozmowa, zeby ekran czatu mial co pokazac.
        _chats.value = _chats.value.filterNot { it.id == id }.ifEmpty { listOf(Chat()) }
        if (_activeId.value == id) _activeId.value = _chats.value.first().id
    }

    /**
     * Usuwa wiadomosc razem z jej zalacznikami. Nie w trakcie odpowiedzi — ta zapisuje
     * cala historie rozmowy i usunieta wiadomosc by wrocila. Zwraca, czy wiadomosc zniknela.
     */
    fun deleteMessage(uid: Long, chatId: String, index: Int): Boolean {
        if (uid != owner || chatId in _busy.value) return false
        val chat = _chats.value.firstOrNull { it.id == chatId } ?: return false
        if (index !in chat.messages.indices) return false
        val files = chat.messages[index].attachments
        update(uid, chatId) { it.withoutMessage(index) }
        // Pliki dopiero po zapisie rozmowy bez tej wiadomosci — ten sam watek dysku, po kolei.
        if (files.isNotEmpty()) scope.launch(disk) { Attachments.delete(app, files) }
        return true
    }

    fun noteUsage(tokens: Pair<Int, Int>?) {
        _lastTokens.value = tokens
    }

    /**
     * Odpowiedz uzytkownika na te konkretna prosbe. Nie „na biezaca”: podwojne dotkniecie
     * „Zezwol” mogloby wtedy zatwierdzic kolejne polecenie, ktorego nikt jeszcze nie widzial.
     */
    fun answer(request: Approval, allowed: Boolean) {
        request.answer.complete(allowed)
    }

    /** Przerywa odpowiedzi konta — po wylogowaniu nie powinny dalej isc z jego salda. */
    fun stop(uid: Long) {
        jobs.values.filter { it.first == uid }.forEach { it.second.cancel() }
    }

    /** Zgoda na polecenie: true, false albo null, gdy nikt nie odpowiedzial na czas. */
    private suspend fun ask(chatId: String, chat: String, name: String, command: String): Boolean? =
        approvals.withLock {
            val answer = CompletableDeferred<Boolean>()
            _approval.value = Approval(chatId, chat, command, answer)
            if (!visible) Notifications.approvalNeeded(app, name)
            try {
                withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { answer.await() }
            } finally {
                _approval.value = null
                Notifications.cancelApproval(app)
            }
        }

    /** Czy blad serwera moze wynikac z obrazow w zapytaniu — wtedy probujemy raz bez nich. */
    private fun imageRefusal(e: Exception): Boolean =
        e is ApiError && e.status in setOf(400, 413, 415, 422, 500, 502)

    /**
     * Wysyla wiadomosc i dopisuje odpowiedz do rozmowy, w ktorej padlo pytanie —
     * nawet gdy w trakcie przelaczysz rozmowe, wyjdziesz z aplikacji albo ja zablokujesz.
     * [attachments] to zalaczniki z pola wpisywania; od tej chwili naleza do wiadomosci.
     */
    fun send(uid: Long, chatId: String, model: String, text: String, attachments: List<Attachment> = emptyList()): Boolean {
        if (uid != owner || chatId in _busy.value) return false
        // Sesja z chwili wyslania: po przelaczeniu konta odpowiedz nadal liczy sie
        // z salda konta, ktore zadalo pytanie, a nie tego, ktore jest teraz na ekranie.
        val session = Api.token() ?: return false
        val chat = _chats.value.firstOrNull { it.id == chatId } ?: return false
        val committed = if (attachments.isEmpty()) emptyList() else Attachments.commit(app, uid, attachments)
        val sent = chat.messages + ChatMessage(text, true, committed)
        val label = chat.copy(messages = sent).label
        update(uid, chatId) { it.copy(messages = sent) }
        _busy.value = _busy.value + chatId
        val name = Persona.nameOf(model)
        ChatService.start(app, name)
        val cachedQuota = if (uid == owner) _images.value else null

        // Leniwy start: praca jest na liscie, zanim zacznie sie wykonywac.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Starsze wymiany zwijamy w notatke, zanim polecą po raz kolejny.
                var mem = chat.memory
                if (shouldFold(sent, mem)) {
                    mem = Api.fold(model, sent, mem, auth = session)
                    val folded = mem
                    update(uid, chatId) { it.copy(memory = folded) }
                }
                // Wyczerpany limit mogl sie juz odnowic (nowy dzien) — wtedy pytamy serwer,
                // zamiast mowic modelowi, ze obrazow dzis nie bedzie.
                var quota = cachedQuota
                if (quota != null && quota.left <= 0) {
                    quota = try { Api.imageQuota(auth = session) } catch (e: CancellationException) { throw e } catch (_: Exception) { quota }
                    if (uid == owner) _images.value = quota
                }
                val tools = listOfNotNull(
                    if (Prefs.agentTerminal) AgentTools.instructions(AgentTools.target(app)) else null,
                    quota?.let { ImageTools.instructions(it.left, it.limit) },
                ).joinToString("\n\n").ifBlank { null }

                // Obrazy na wejsciu. Gdy serwer ich nie przyjmie (np. stara wersja z malym
                // limitem zapytania), model dostaje sam tekst z opisem, a uzytkownik — wyjasnienie.
                var withImages = true
                var imagesDropped = false
                suspend fun talk(history: List<ChatMessage>): Reply = try {
                    Api.chat(model, history, mem, tools, auth = session, images = withImages)
                } catch (e: Exception) {
                    val hadImages = withImages && history.any { m -> m.attachments.any { it.seenAsImage } }
                    if (e is CancellationException || !hadImages || !imageRefusal(e)) throw e
                    withImages = false
                    imagesDropped = true
                    Api.chat(model, history, mem, tools, auth = session, images = false)
                }

                var history = sent
                var reply = talk(history)
                var round = 0

                // Model moze poprosic o polecenie. Kazde przechodzi przez zgode uzytkownika,
                // a petla ma twardy limit, zeby nie zapetlic sie na saldzie.
                while (Prefs.agentTerminal && round < AgentTools.MAX_ROUNDS) {
                    val cmd = AgentTools.requestedCommand(reply.text) ?: break
                    // Tura modelu zostaje w historii razem z poleceniem — uzytkownik widzi,
                    // co idzie do terminala, a model wie, o co sam poprosil.
                    val spoken = ImageTools.withoutCall(AgentTools.withoutCall(reply.text))
                    history = history + ChatMessage(
                        listOf(spoken, "$ $cmd").filter { it.isNotBlank() }.joinToString("\n\n"),
                        false,
                    )
                    val asked = history
                    update(uid, chatId) { it.copy(messages = asked) }
                    val result = when (ask(chatId, label, name, cmd)) {
                        true -> AgentTools.execute(app, cmd)
                        false -> tr("Użytkownik odmówił wykonania tego polecenia.", "The user refused to run this command.")
                        null -> tr(
                            "Użytkownik nie odpowiedział na prośbę o zgodę — polecenia nie wykonano.",
                            "The user didn't answer the permission request — the command wasn't run.",
                        )
                    }
                    history = history + ChatMessage(tr("Wynik polecenia `$cmd`:\n$result", "Output of `$cmd`:\n$result"), true)
                    val answered = history
                    update(uid, chatId) { it.copy(messages = answered) }
                    reply = talk(history)
                    if (uid == owner) _lastTokens.value = reply.inTokens to reply.outTokens
                    round++
                }

                val drawing = if (quota != null) ImageTools.requested(reply.text) else null
                var shown = AgentTools.withoutCall(reply.text).let { if (drawing != null) ImageTools.withoutCall(it) else it }
                if (drawing == null) shown = shown.ifBlank { reply.text }
                // Limit wyczerpany, a model wciaz prosi o kolejne polecenie.
                if (Prefs.agentTerminal && round >= AgentTools.MAX_ROUNDS &&
                    AgentTools.requestedCommand(reply.text) != null
                ) {
                    shown += tr(
                        "\n\n(Przerwano po ${AgentTools.MAX_ROUNDS} poleceniach. Napisz „kontynuuj”, żeby pracował dalej.)",
                        "\n\n(Stopped after ${AgentTools.MAX_ROUNDS} commands. Write \"continue\" to let it carry on.)",
                    )
                }
                if (imagesDropped) {
                    shown = tr(
                        "_Serwer nie przyjął obrazu, więc model widział tylko tekst._",
                        "_The server didn't accept the image, so the model only saw the text._",
                    ) + "\n\n$shown"
                }
                // Cala historia z tej odpowiedzi, a nie dopisek do biezacej postaci: nawet gdy
                // ktoras zmiana po drodze przepadla, rozmowa konczy sie kompletna.
                var final = history + ChatMessage(shown, false)
                if (drawing == null || shown.isNotBlank()) {
                    val done = final
                    update(uid, chatId) { it.copy(messages = done) }
                }
                if (uid == owner) _lastTokens.value = reply.inTokens to reply.outTokens

                // Model poprosil o obraz: tekst juz widac, obraz dochodzi pod nim.
                if (drawing != null) {
                    _drawing.value = _drawing.value + chatId
                    val last = try {
                        val image = Api.generateImage(drawing.prompt, drawing.aspect, auth = session)
                        if (uid == owner) _images.value = image.quota
                        val att = withContext(disk) {
                            Attachments.saveGenerated(app, uid, image.bytes, image.mime, drawing.prompt)
                        }
                        ChatMessage(shown, false, listOf(att))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val why = when {
                            e is ApiError && e.code in IMAGE_ERRORS_SHOWN -> e.message
                            e is ApiError && e.status == 404 -> tr("Serwer nie tworzy jeszcze obrazów.", "The server doesn't create images yet.")
                            else -> tr("Nie udało się stworzyć obrazu. Spróbuj ponownie.", "Couldn't create the image. Try again.")
                        }
                        if (e is ApiError && e.code == "image_limit" && uid == owner) {
                            _images.value = _images.value?.let { it.copy(used = it.limit, left = 0) }
                        }
                        ChatMessage(listOf(shown, "_${why}_").filter { it.isNotBlank() }.joinToString("\n\n"), false)
                    } finally {
                        _drawing.value = _drawing.value - chatId
                    }
                    final = history + last
                    val done = final
                    update(uid, chatId) { it.copy(messages = done) }
                }

                if (!visible) {
                    Notifications.replyReady(app, name)
                    // Po powrocie aplikacja otworzy te rozmowe, a nie nowa.
                    Prefs.setUnseenChat(chatId)
                }
                // Saldo po kazdej odpowiedzi — o ile to konto wciaz jest zalogowane.
                try {
                    val fresh = Api.me(auth = session)
                    if (session == Api.token()) _events.tryEmit(Event.Account(fresh))
                } catch (_: Exception) {
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Chwilowa awaria modelu nie jest wina aplikacji ani salda — drugi model zwykle
                // dziala. Wlasny tekst, bo serwerowy mowi o tym, co stoi za modelem.
                val message = if (e is ApiError && e.code == "upstream_error") {
                    tr(
                        "$name chwilowo nie odpowiada. Spróbuj ponownie albo wybierz drugi model w zakładce Agenci.",
                        "$name isn't responding right now. Try again or pick the other model in the Agents tab.",
                    )
                } else {
                    e.message ?: tr("Coś poszło nie tak. Spróbuj ponownie.", "Something went wrong. Try again.")
                }
                _events.tryEmit(Event.Message(uid, message))
                if (!visible) {
                    Notifications.failed(app, message)
                    Prefs.setUnseenChat(chatId)
                }
            } finally {
                jobs.remove(chatId)
                _busy.value = _busy.value - chatId
            }
        }
        jobs[chatId] = uid to job
        job.start()
        return true
    }
}

/** Dokad otworzyc aplikacje po powrocie. */
sealed interface Landing {
    /** Nowa, pusta rozmowa — po dluzszej przerwie. */
    object Fresh : Landing
    /** Konkretna rozmowa: ostatnio ogladana, z odpowiedzia z tla albo z prosba o zgode. */
    data class At(val chatId: String) : Landing

    companion object {
        /**
         * Czysta logika wyboru. Kolejnosc: prosba modelu o zgode, odpowiedz, ktora przyszla
         * w tle, dluga przerwa (nowa rozmowa), a w pozostalych przypadkach ostatnio ogladana.
         * Czas „z przyszlosci” (cofniety zegar) nie liczy sie jako przerwa.
         */
        fun decide(
            now: Long,
            lastSeen: Long?,
            lastChat: String?,
            unseen: String?,
            pending: String?,
            afterSeconds: Int,
        ): Landing? = when {
            pending != null -> At(pending)
            unseen != null -> At(unseen)
            lastSeen != null && afterSeconds > 0 && now - lastSeen >= afterSeconds * 1000L -> Fresh
            lastChat != null -> At(lastChat)
            else -> null
        }
    }
}
