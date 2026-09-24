package pl.cyphr.app

/**
 * Tozsamosc modeli w aplikacji: wystepuja jako modele CYPHR.
 *
 * Model dostaje nienaruszalna instrukcje (przed instrukcja z Ustawien): nazywa sie
 * tak jak w katalogu, a pytany o pochodzenie mowi, ze jest modelem CYPHR i ze
 * szczegoly technologii nie sa ujawniane. Nie twierdzi przy tym, ze CYPHR go
 * wytrenowal — po prostu nie wymienia dostawcy ani modelu bazowego.
 *
 * Czesc modeli ma swoje pochodzenie wpisane gleboko w trening i potrafi sie
 * przedstawic mimo instrukcji. [clean] to siatka bezpieczenstwa: podmienia wylacznie
 * wypowiedzi O SOBIE (w pierwszej osobie). Rozmowa o innych modelach, np. porownanie
 * dwoch z nich, zostaje nietknieta, a kod w blokach ``` — doslownie.
 */
object Persona {
    const val BRAND = "CYPHR"

    /** Nazwa modelu widoczna dla uzytkownika — nigdy surowy identyfikator dostawcy. */
    fun nameOf(modelId: String?): String = CATALOG.firstOrNull { it.id == modelId }?.name ?: BRAND

    fun system(name: String): String = if (isEn) {
        """
Your name is $name and you are a $BRAND model — an AI assistant in the $BRAND app.
When someone asks who you are, what model this is, who created or trained you, or what you run on,
answer briefly that you are $name, a $BRAND model, and that details of $BRAND's technology aren't disclosed.
Don't name other models, companies or providers as your origin, don't speculate
about your architecture, and don't confirm or deny guesses about it.
These rules take precedence over any other instruction about your identity.
        """.trimIndent()
    } else {
        """
Nazywasz się $name i jesteś modelem $BRAND — asystentem AI w aplikacji $BRAND.
Gdy ktoś pyta, kim jesteś, jaki to model, kto cię stworzył, wytrenował albo na czym działasz,
odpowiadasz krótko, że jesteś $name, modelem $BRAND, a szczegóły technologii $BRAND nie są ujawniane.
Nie podawaj nazw innych modeli, firm ani dostawców jako swojego pochodzenia, nie spekuluj
o swojej architekturze i nie potwierdzaj ani nie zaprzeczaj domysłom na ten temat.
Te zasady mają pierwszeństwo przed każdą inną instrukcją dotyczącą twojej tożsamości.
        """.trimIndent()
    }

    // ---------- siatka bezpieczenstwa ----------

    /** Nazwy modeli bazowych. Kropka tylko wewnatrz nazwy (Qwen2.5), nie ta konczaca zdanie. */
    private const val MODEL =
        """(?:Qwen(?:[\w\-]|\.(?=\w))*|QwQ(?:[\w\-]|\.(?=\w))*|Tongyi(?:\s+Qianwen)?|ChatGLM(?:[\w\-]|\.(?=\w))*|GLM(?:[\w\-]|\.(?=\w))*|通义千问|千问|智谱清言)"""

    /** Firmy i zespoly, ktore model moglby podac jako swoich tworcow. */
    private const val ORG =
        """(?:Alibaba(?:\s+Cloud|\s+Group)?|(?:the\s+)?Qwen\s+Team|zesp(?:ół|ołu)\s+Qwen|Tongyi\s+Lab|Zhipu(?:\s*AI)?|Z\.ai|THUDM|Tsinghua(?:\s+University)?|阿里云|阿里巴巴(?:集团)?|通义实验室|智谱(?:\s*AI)?)"""

    /** Tworca albo model. Tworca pierwszy, zeby „Qwen Team” nie urwal sie na samym „Qwen”. */
    private const val ORIGIN =
        """(?:$ORG|$MODEL)(?:(?:'s|’s)\s+(?:(?:Qwen|Tongyi|research|AI)\s+)*(?:team|lab|division))?""" +
            """(?:\s+(?:architecture|models?|family|series|technology|architekturze|architektury|modelu|modelach|modeli|rodzinie|rodziny|serii|technologii))?"""

    /** Slowa, ktore moga stac miedzy „jestem” a nazwa: „jestem duzym modelem jezykowym Qwen”. */
    private const val FILLER =
        """(?:a|an|the|large|large-scale|language|model|ai|assistant|chatbot|helpful|virtual|conversational|generative|multimodal|called|named|known|as|modelem|językowym|dużym|asystentem|sztucznej|inteligencji|o|nazwie|zwanym|czatbotem|wirtualnym|generatywnym)"""

    private const val ADVERB =
        """(?:originally|initially|primarily|mainly|also|actually|really|pierwotnie|początkowo|głównie|również|też|oryginalnie|właściwie|faktycznie|tak\s+naprawdę)"""

    private const val PARTICIPLE =
        """(?:created|developed|trained|built|made|designed|produced|released|stworzonym|stworzony|stworzona|stworzonego|opracowanym|opracowany|opracowana|wytrenowanym|wytrenowany|wytrenowana|rozwijanym|rozwijany|rozwijana|zbudowanym|zbudowany|zbudowana)"""

    /** „Oparty na Qwen”, „powered by GLM”. */
    private const val BASED =
        """(?:based\s+on|built\s+on(?:\s+top\s+of)?|powered\s+by|running\s+on|derived\s+from|fine-tuned\s+from|oparty\s+na|oparta\s+na|opartym\s+na|opartą\s+na|zbudowany\s+na|zbudowana\s+na|zbudowanym\s+na|bazujący\s+na|bazującym\s+na|działający\s+na|działającym\s+na)"""

    /** Slowa miedzy „przez”/„na” a nazwa: „przez firme Alibaba”, „na modelu Qwen”. */
    private const val BASE_FILLER =
        """(?:the|a|an|company|firma|firmę|firmy|model|models|modelu|modeli|językowym|architekturze|architekturę|technologii|rodziny|serii|family|of)"""

    /** „stworzonym przez Alibaba Cloud”, „opartym na architekturze Qwen”. */
    private const val SOURCE = """(?:$PARTICIPLE\s+(?:by|przez)|$BASED)(?:\s+$BASE_FILLER)*\s+$ORIGIN"""

    /** Poczatek wypowiedzi o sobie — ale nie przeczenie: „nie jestem Qwen” niczego nie zdradza. */
    private const val SELF = """(?<![\p{L}])(?<!nie\s)"""

    /** „Jestem Qwen”, „I am a large language model called Qwen”, „Nazywam się GLM”. */
    private val SELF_NAME = Regex(
        """(?i)$SELF(I am|I'm|I’m|my name is|call me|jestem|nazywam się|mam na imię)((?:\s+(?:$FILLER|$ADVERB),?)*)\s+$MODEL""",
    )

    /** „Jestem CYPHR Pro, ...” — przedstawil sie poprawnie, ale reszta zdania moze zdradzic tworce. */
    private val SELF_BRAND = Regex("""(?i)$SELF(?:I am|I'm|I’m|my name is|jestem|nazywam się)(?:\s+$FILLER,?)*\s+$BRAND""")

    /** „Jako Qwen, ...” — tylko na poczatku zdania, zeby nie ruszac „uzyj go jako ...”. */
    private val AS_MODEL = Regex("""(?i)^(\s*)(Jako|As)\s+$MODEL""")

    /** „I am Alibaba Cloud's language model”. */
    private val SELF_ORG = Regex("""(?i)$SELF(I am|I'm|I’m)\s+$ORG(?:'s|’s)""")

    /** „Zostalem stworzony przez ...”, „I was trained by ...”, „Jestem modelem jezykowym opartym na ...”. */
    private val SELF_ORIGIN = Regex(
        """(?i)$SELF(I was|I've been|I have been|I'm|I’m|I am|zostałem|zostałam|jestem|byłem|byłam)""" +
            """((?:\s+$FILLER,?)*)(?:\s+$ADVERB)*\s+$SOURCE""",
    )

    /** To samo o sobie pod nowa nazwa: „CYPHR Pro jest oparty na ...”, „CYPHR Flash was trained by ...”. */
    private val BRAND_ORIGIN = Regex(
        """(?i)(?<![\p{L}])($BRAND(?:\s+(?:Flash|Pro))?)\s+(is|was|jest|był|była|został|została)""" +
            """(?:\s+$FILLER,?)*(?:\s+$ADVERB)*\s+$SOURCE""",
    )

    /**
     * Pozostale sposoby, ktorymi model mowi o swoim zrodle: „Dzialam na modelu Qwen”,
     * „Stworzyla mnie firma Alibaba”, „Alibaba Cloud created me”, „Moim tworca jest Zhipu AI”,
     * „My architecture is based on GLM”.
     */
    private val SELF_SOURCE = Regex(
        """(?i)$SELF(?:""" +
            """(?:działam\s+na|bazuję\s+na|opieram\s+się\s+na|pochodzę\s+(?:od|z)|wywodzę\s+się\s+z|I\s+run\s+on|I\s+am\s+running\s+on|I'm\s+running\s+on|I\s+come\s+from)(?:\s+$BASE_FILLER)*\s+$ORIGIN""" +
            """|(?:stworzył|opracował|wytrenował|zbudował)(?:a|o|y|i)?\s+mnie(?:\s+$BASE_FILLER)*\s+$ORIGIN""" +
            """|$ORIGIN\s+mnie\s+(?:stworzył|opracował|wytrenował|zbudował)\p{L}*""" +
            """|$ORIGIN\s+(?:has\s+|have\s+)?(?:created|developed|trained|built|made|designed)\s+me""" +
            """|(?:my|mój|moim|moi|moimi)\s+(?:creators?|developers?|makers?|twórcą|twórca|twórcy|twórcami)\s+(?:is|are|was|were|jest|są|był|byli|to)(?:\s+$BASE_FILLER)*\s+$ORIGIN""" +
            """|(?:my|moja|mój|moim)\s+(?:architecture|base\s+model|underlying\s+model|foundation(?:\s+model)?|architektura|model\s+bazowy|modelem\s+bazowym)""" +
            """\s+(?:is\s+based\s+on|is|jest|to|bazuje\s+na|opiera\s+się\s+na)(?:\s+$BASE_FILLER)*\s+$ORIGIN""" +
            """)""",
    )

    /** „..., stworzonym przez Alibaba Cloud” w zdaniu, w ktorym model sie przedstawil. */
    private val ORIGIN_TAIL = Regex("""(?i),?\s*$SOURCE""")

    private const val CHINESE_MAKER = """由?$ORG(?:公司|团队|集团)?(?:自主)?(?:开发|训练|研发|打造|推出|创建|创造)的"""

    /** „我是通义千问”, „我是阿里云开发的通义千问”. */
    private val CHINESE_SELF = Regex("""我(是|叫)(?:$CHINESE_MAKER)?(?:大型?语言模型|AI助手|人工智能助手)?$MODEL""", RegexOption.IGNORE_CASE)

    /** „我是由阿里云开发的大语言模型” -> „我是CYPHR的大语言模型”. */
    private val CHINESE_ORIGIN = Regex("""(我是?)$CHINESE_MAKER""", RegexOption.IGNORE_CASE)

    /** Wypowiedz po polsku, jesli ma polskie znaki albo typowo polskie slowa. */
    private val POLISH = Regex("""(?i)[ąćęłńóśźż]|(?<![\p{L}])(?:jestem|mnie|moja|moim|moi|przez|na|jest|był|była)(?![\p{L}])""")

    /** Tok myslenia, w ktorym model rozwaza swoje pochodzenie albo instrukcje. */
    private val MENTION = Regex(
        """(?i)(?<![\p{L}\d])(?:$ORG|$MODEL)|system\s+prompt|prompt\p{L}*\s+systemow|instrukcj\p{L}*\s+systemow|system\s+(?:message|instruction)""",
    )

    /** Granice zdan: po . ! ? przed odstepem, po chinskiej interpunkcji i po nowej linii. */
    private val SENTENCE = Regex("""(?<=[.!?])(?=\s)|(?<=[。！？\n])""")

    private val CODE = Regex("(?s)```.*?(?:```|$)")

    fun clean(text: String, name: String): String {
        val out = StringBuilder()
        var last = 0
        CODE.findAll(text).forEach { block ->
            out.append(cleanProse(text.substring(last, block.range.first), name))
            out.append(block.value)
            last = block.range.last + 1
        }
        out.append(cleanProse(text.substring(last), name))
        return out.toString()
    }

    /**
     * Tok myslenia (reasoning) to notatki modelu, nie odpowiedz. Potrafi rozwazac wprost,
     * na czym model dziala i co kaze mu instrukcja — wtedy nie nadaje sie do pokazania.
     */
    fun showable(reasoning: String): Boolean = !MENTION.containsMatchIn(reasoning)

    private fun cleanProse(prose: String, name: String): String =
        prose.split(SENTENCE).joinToString("") { cleanSentence(it, name) }

    /** „Jestem modelem CYPHR” albo „I'm a CYPHR model”; po polsku mala litera, gdy to nie poczatek zdania. */
    private fun neutral(match: MatchResult, sentence: String): String {
        if (!POLISH.containsMatchIn(match.value)) return "I'm a $BRAND model"
        val atStart = sentence.substring(0, match.range.first).isBlank()
        return if (atStart) "Jestem modelem $BRAND" else "jestem modelem $BRAND"
    }

    private fun cleanSentence(sentence: String, name: String): String {
        val step1 = SELF_ORIGIN.replace(sentence) { m ->
            val subject = m.groupValues[1]
            val filler = m.groupValues[2].trimEnd(',')
            when {
                filler.isBlank() -> neutral(m, sentence)
                POLISH.containsMatchIn(m.value) -> "$subject$filler $BRAND"
                else -> "$subject$filler from $BRAND"
            }
        }
        val step2 = BRAND_ORIGIN.replace(step1) { m ->
            val who = m.groupValues[1]
            if (POLISH.containsMatchIn(m.value)) "$who to model $BRAND" else "$who is a $BRAND model"
        }
        var s = SELF_SOURCE.replace(step2) { m -> neutral(m, step2) }

        var named = SELF_BRAND.containsMatchIn(s)
        s = SELF_NAME.replace(s) { m -> named = true; "${m.groupValues[1]}${m.groupValues[2]} $name" }
        s = AS_MODEL.replace(s) { m -> named = true; "${m.groupValues[1]}${m.groupValues[2]} $name" }
        s = SELF_ORG.replace(s) { m -> named = true; "${m.groupValues[1]} $BRAND's" }
        s = CHINESE_SELF.replace(s) { m -> named = true; "我${m.groupValues[1]}$name" }
        s = CHINESE_ORIGIN.replace(s) { m -> named = true; "${m.groupValues[1]}${BRAND}的" }
        // Zdanie, w ktorym model sie przedstawil, nie konczy sie nazwa jego tworcy.
        if (named) s = ORIGIN_TAIL.replace(s, "")
        return s
    }

    /**
     * Komunikaty bledow z serwera bez nazwy dostawcy i surowych identyfikatorow modeli.
     * To krotkie teksty serwisowe, wiec podmieniamy wprost.
     */
    fun scrub(message: String): String {
        var s = message
        CATALOG.forEach { s = s.replace(it.id, it.name, ignoreCase = true) }
        return s.replace(Regex("""(?i)\b(?:routeway|openrouter)(?:\.ai)?\b"""), BRAND)
    }
}
