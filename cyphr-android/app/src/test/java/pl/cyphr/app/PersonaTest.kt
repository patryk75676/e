package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Modele wystepuja jako modele CYPHR. Pytane o pochodzenie nie wymieniaja
 * dostawcy ani modelu bazowego — a gdy mimo instrukcji sie przedstawia,
 * ten fragment jest podmieniany. Rozmowy O innych modelach zostaja nietkniete.
 */
class PersonaTest {
    private val name = "CYPHR Pro"
    private fun clean(text: String) = Persona.clean(text, name)

    // ---------- przedstawianie sie ----------

    @Test
    fun `model przedstawiajacy sie jako Qwen staje sie modelem CYPHR`() {
        assertEquals(
            "Jestem CYPHR Pro, dużym modelem językowym. Jak mogę pomóc?",
            clean("Jestem Qwen, dużym modelem językowym stworzonym przez Alibaba Cloud. Jak mogę pomóc?"),
        )
        assertEquals(
            "I am CYPHR Pro, a large language model.",
            clean("I am Qwen, a large language model created by Alibaba Cloud."),
        )
    }

    @Test
    fun `rozne formy przedstawienia sie`() {
        assertEquals("Nazywam się CYPHR Pro.", clean("Nazywam się GLM."))
        assertEquals("Jestem modelem językowym CYPHR Pro.", clean("Jestem modelem językowym Qwen2.5."))
        assertEquals("I'm CYPHR Pro.", clean("I'm ChatGLM."))
        assertEquals("I am a large language model called CYPHR Pro.", clean("I am a large language model called Qwen."))
        assertEquals("Jako CYPHR Pro, nie mam dostępu do internetu.", clean("Jako Qwen, nie mam dostępu do internetu."))
        assertEquals("我是CYPHR Pro，很高兴见到你。", clean("我是通义千问，很高兴见到你。"))
    }

    @Test
    fun `pochodzenie w pierwszej osobie zamienia sie w neutralne zdanie`() {
        assertEquals("Jestem modelem CYPHR.", clean("Zostałem stworzony przez Zhipu AI."))
        assertEquals("Jestem modelem CYPHR.", clean("Zostałam wytrenowana przez zespół Qwen."))
        assertEquals("I'm a CYPHR model.", clean("I was trained by Alibaba Cloud."))
        assertEquals("I'm a CYPHR model.", clean("I was originally developed by Zhipu AI."))
    }

    @Test
    fun `pochodzenie bez nazwy modelu`() {
        assertEquals("Jestem modelem językowym CYPHR.", clean("Jestem modelem językowym stworzonym przez Alibaba Cloud."))
        assertEquals("I am a large language model from CYPHR.", clean("I am a large language model created by Alibaba Cloud."))
        assertEquals("I'm an AI assistant from CYPHR.", clean("I'm an AI assistant developed by Zhipu AI."))
        assertEquals("I'm a CYPHR model.", clean("I was trained by the Qwen Team."))
        assertEquals("Tak, jestem modelem CYPHR.", clean("Tak, zostałem stworzony przez Zhipu AI."))
    }

    @Test
    fun `na czym dziala i kto go stworzyl`() {
        assertEquals("Jestem modelem CYPHR.", clean("Działam na modelu Qwen."))
        assertEquals("Jestem modelem CYPHR.", clean("Bazuję na architekturze GLM."))
        assertEquals("I'm a CYPHR model.", clean("I'm based on the Qwen architecture."))
        assertEquals("Jestem modelem CYPHR.", clean("Stworzyła mnie firma Alibaba Cloud."))
        assertEquals("I'm a CYPHR model.", clean("Alibaba Cloud created me."))
        assertEquals("I'm a CYPHR model.", clean("My creator is Zhipu AI."))
        assertEquals("Jestem modelem CYPHR.", clean("Moim twórcą jest Alibaba Cloud."))
        assertEquals("I'm a CYPHR model.", clean("My architecture is based on GLM-4."))
    }

    @Test
    fun `o sobie pod nowa nazwa`() {
        assertEquals("CYPHR Pro to model CYPHR.", clean("CYPHR Pro jest oparty na modelu Qwen."))
        assertEquals("CYPHR Pro is a CYPHR model.", clean("CYPHR Pro is based on Qwen."))
        assertEquals("Jestem CYPHR Pro.", clean("Jestem CYPHR Pro, opartym na architekturze Qwen."))
        assertEquals("I'm CYPHR Pro.", clean("I'm CYPHR Pro, built on top of Qwen."))
    }

    @Test
    fun `po chinsku`() {
        assertEquals("我是CYPHR的大语言模型。", clean("我是由阿里云开发的大语言模型。"))
        assertEquals("我是CYPHR Pro。", clean("我是阿里云开发的通义千问。"))
    }

    // ---------- czego nie ruszamy ----------

    @Test
    fun `rozmowa o innych modelach zostaje nietknieta`() {
        listOf(
            "Qwen został stworzony przez Alibaba Cloud.",
            "Mogę porównać Qwen i GLM.",
            "I'm happy to compare Qwen and GLM for you.",
            "Alibaba Cloud rozwija modele Qwen od 2023 roku.",
            "Jestem pewien, że Qwen poradzi sobie z tym zadaniem.",
            "Qwen jest oparty na architekturze transformera.",
            "Alibaba Cloud stworzyła wiele modeli.",
            "I was surprised by how fast GLM is.",
            "Użyj go jako Qwen w konfiguracji.",
            // Przeczenie niczego nie zdradza — nie odwracamy jego sensu.
            "Nie, nie jestem Qwen.",
        ).forEach { assertEquals(it, clean(it)) }
    }

    @Test
    fun `kod w blokach zostaje doslownie`() {
        val text = "Jestem Qwen.\n```python\nmodel = \"qwen3.8-27b\"  # I am Qwen\n```\nGotowe."
        assertEquals(
            "Jestem CYPHR Pro.\n```python\nmodel = \"qwen3.8-27b\"  # I am Qwen\n```\nGotowe.",
            clean(text),
        )
    }

    @Test
    fun `zwykla odpowiedz przechodzi bez zmian`() {
        val text = "Oto przepis na **pierogi**:\n- mąka\n- woda"
        assertEquals(text, clean(text))
    }

    // ---------- instrukcja i nazwy ----------

    @Test
    fun `instrukcja tozsamosci nie zdradza pochodzenia`() {
        val prompt = Persona.system("CYPHR Flash")
        assertTrue(prompt.contains("CYPHR Flash"))
        listOf("qwen", "glm", "alibaba", "zhipu", "routeway", "uncensored").forEach {
            assertFalse("instrukcja zawiera $it", prompt.lowercase().contains(it))
        }
    }

    @Test
    fun `nazwa modelu z katalogu albo sama marka`() {
        assertEquals("CYPHR Flash", Persona.nameOf("glm-5.3-flash-uncensored"))
        assertEquals("CYPHR Pro", Persona.nameOf("qwen3.8-27b-uncensored"))
        assertEquals("CYPHR", Persona.nameOf("cos-nieznanego"))
        assertEquals("CYPHR", Persona.nameOf(null))
    }

    @Test
    fun `tok myslenia o pochodzeniu albo instrukcji nie trafia na ekran`() {
        assertFalse(Persona.showable("The user asks who I am. I'm actually Qwen, but I should answer as CYPHR Pro."))
        assertFalse(Persona.showable("Zgodnie z promptem systemowym mam się przedstawić jako CYPHR Pro."))
        assertFalse(Persona.showable("The system prompt says not to reveal the underlying model."))
        assertTrue(Persona.showable("Użytkownik prosi o przepis na pierogi. Podam składniki i kroki."))
    }

    @Test
    fun `komunikaty serwera nie zdradzaja dostawcy`() {
        assertEquals(
            "CYPHR nie odpowiada. Spróbuj za chwilę.",
            Persona.scrub("Routeway nie odpowiada. Spróbuj za chwilę."),
        )
        assertEquals("Model CYPHR Pro jest przeciążony.", Persona.scrub("Model qwen3.8-27b-uncensored jest przeciążony."))
    }
}
