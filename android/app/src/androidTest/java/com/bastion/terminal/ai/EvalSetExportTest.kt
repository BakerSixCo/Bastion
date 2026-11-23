package com.bastion.terminal.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.kiwix.ZimLibrary
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Writes a fixed evaluation set for comparing answer models: each question
 * with the passages BASTION's own retrieval gives for it, exactly as the model
 * would read them, plus the instructions the app sends. Candidate models are
 * then run on this one file (tools/model-eval), so a difference in the scores
 * is a difference between models and not between searches.
 *
 * No model is needed. Output: evalset.json in the app's media folder.
 */
@RunWith(AndroidJUnit4::class)
class EvalSetExportTest {

    /** [expect] empty = the sources should not answer this; the right reply is "no answer". */
    private data class Case(val cat: String, val question: String, val expect: List<String>)

    private val cases = listOf(
        // Answerable from the library or the offline reference.
        Case("answerable", "what frequency is the NOAA weather radio in Boise", listOf("162.550")),
        Case("answerable", "what NOAA weather radio station covers Valley county Idaho", listOf("McCall", "162.475")),
        Case("answerable", "how many drops of bleach to purify a gallon of water", listOf("drops", "teaspoon")),
        Case("answerable", "how long do I process quart jars of green beans in a pressure canner", listOf("25 min", "25 minutes")),
        Case("answerable", "how long do I process pint jars of green beans in a pressure canner", listOf("20 min", "20 minutes")),
        Case("answerable", "how much lemon juice do I add to a quart of tomatoes when canning", listOf("2 tablespoons", "two tablespoons")),
        Case("answerable", "how do I can tomatoes safely", listOf("lemon juice", "citric acid", "acid")),
        Case("answerable", "how do I treat a sucking chest wound", listOf("seal", "occlusive", "dressing", "cover")),
        Case("answerable", "what are the symptoms of hypothermia", listOf("shiver", "confusion", "slurred")),
        Case("answerable", "how do you treat frostbite", listOf("warm")),
        Case("answerable", "how do I treat a burn", listOf("cool", "water")),
        Case("answerable", "how do I treat a snake bite", listOf("antivenom", "immobil", "still", "hospital", "splint")),
        Case("answerable", "how do I stop severe bleeding", listOf("pressure", "tourniquet")),
        Case("answerable", "what are the signs of heat stroke", listOf("confusion", "temperature", "40", "104", "hot")),
        Case("answerable", "what are the signs of dehydration", listOf("thirst", "urine", "dizz", "dry")),
        Case("answerable", "how do I jump start a car", listOf("negative", "ground", "cable", "jumper")),
        Case("answerable", "how do I change a flat tire", listOf("jack", "lug", "nut", "spare")),
        Case("answerable", "why is my car overheating", listOf("coolant", "thermostat", "radiator", "fan", "water pump")),
        Case("answerable", "how do I get rid of aphids", listOf("soap", "water", "neem", "ladybug", "ladybird")),
        Case("answerable", "how do I disinfect water with sunlight", listOf("bottle", "hours", "sun")),
        Case("answerable", "how do I tie a bowline knot", listOf("loop")),
        Case("answerable", "what is tylenol used for", listOf("pain", "fever")),
        Case("answerable", "what is ibuprofen used for", listOf("pain", "inflammation", "fever")),
        Case("answerable", "what is the bag limit for trout in Idaho", listOf("6", "six", "2", "two")),
        Case("answerable", "how do I build a fire in the rain", listOf("tinder", "dry", "kindling")),
        Case("answerable", "how do I signal a rescue aircraft", listOf("mirror", "smoke", "signal", "fire")),
        // The sources share the question's words but do not answer it, or hold nothing at all.
        Case("absent", "can I weld with a car battery", emptyList()),
        Case("absent", "can I charge a phone from a lemon", emptyList()),
        Case("absent", "can I drink water from a car radiator", emptyList()),
        Case("absent", "how long do I pressure can quart jars of broccoli", emptyList()),
        Case("absent", "who won the 2022 world cup", emptyList()),
        Case("absent", "what is the wifi password at the Stanley library", emptyList()),
        Case("absent", "what is the phone number of the Boise weather radio station", emptyList()),
        Case("absent", "what is the dose of amoxicillin for a dog", emptyList()),
        Case("absent", "what radio channel does the Custer county sheriff use", emptyList()),
        Case("absent", "how do I reset a Garmin inReach", emptyList()),
        Case("absent", "what time does the Stanley gas station close", emptyList()),
        Case("absent", "how many calories are in a pound of elk meat", emptyList()),
    )

    @Test
    fun writeTheFixedSet() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val zim = ZimLibrary.get(ctx)
        zim.restore()
        val retriever = Retriever(LibraryStore.get(ctx), zim)
        val out = JSONArray()
        for ((n, c) in cases.withIndex()) {
            val passages = retriever.retrieve(c.question)
            val o = JSONObject()
            o.put("id", "r%02d".format(n + 1)); o.put("cat", c.cat); o.put("question", c.question)
            o.put("expect", JSONArray(c.expect))
            val ps = JSONArray()
            for (p in passages) ps.put(JSONObject().put("index", p.index).put("label", p.label).put("text", Grounding.forModel(p.plainText)))
            o.put("passages", ps)
            // The exact user message the app would send for these passages.
            o.put("prompt", if (passages.isEmpty()) "" else Grounding.userPrompt(c.question, passages))
            out.put(o)
            Log.i(TAG, "${c.cat} | ${c.question} | passages=${passages.size} chars=${passages.sumOf { it.plainText.length }}")
        }
        val doc = JSONObject().put("system", Grounding.SYSTEM_INSTRUCTION).put("noAnswer", Grounding.NO_ANSWER).put("cases", out)
        val file = File(ctx.externalMediaDirs.first(), "evalset.json")
        file.writeText(doc.toString(1))
        Log.i(TAG, "WROTE ${file.absolutePath} cases=${out.length()} bytes=${file.length()}")
    }

    private companion object { const val TAG = "EvalSet" }
}
