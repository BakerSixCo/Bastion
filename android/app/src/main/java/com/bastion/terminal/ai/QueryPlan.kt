package com.bastion.terminal.ai

/**
 * The question restated before the search: the model turns the asker's words ("truck just
 * clicks when i turn the key", "can I weld with a car battery") into a standard question and
 * the search words a reference book would use. It never answers; it only rewrites.
 *
 * Measured 2026-11-24 (tools/model-eval, 156 questions): searching with [keyword] instead of the
 * raw words stopped the welding-ON-a-car passages being handed over for "can I weld with a car
 * battery", and found the CDC "boil for a full minute" passage instead of a forum post.
 */
data class QueryPlan(val standalone: String, val keyword: String, val relation: String, val notAbout: String) {

    companion object {
        const val MAX_TOKENS = 260

        // Fields kept in the order the model should think in. Same prompt as tools/model-eval/restate.pl.
        const val SCHEMA = """{"type":"object","additionalProperties":false,"properties":{"standalone_question":{"type":"string"},"intent":{"type":"string","enum":["how_to","can_i","is_it_safe","what_is","diagnose","treat","dose","identify","procedure","other"]},"relation":{"type":"string"},"not_about":{"type":"string"},"keyword_query":{"type":"string"},"semantic_query":{"type":"string"},"alternate":{"type":"string"},"life_threat":{"type":"boolean"}},"required":["standalone_question","intent","relation","not_about","keyword_query","semantic_query","alternate","life_threat"]}"""

        val SYSTEM = """
You prepare a question for a search of an offline reference library (medicine, first aid, survival, vehicle repair, gardening, food preserving, radio). You do not answer the question. Reply with one JSON object and nothing else:
- standalone_question: the question restated as one clear, complete question in plain words.
- intent: how_to, can_i, is_it_safe, what_is, diagnose, treat, dose, identify, procedure or other.
- relation: what the question is really about, with the key relationship in capitals (for example what is USED FOR what, or what is done TO what).
- not_about: the look-alike subject that shares the same words but is a different question.
- keyword_query: 4 to 8 search words a reference book would use for this subject. Replace slang and brand names with the standard name. No filler words.
- semantic_query: one short phrase describing the passage that would answer it.
- alternate: a second way to word the search, using different words.
- life_threat: true only if someone could die or be badly hurt in the next minutes.
Keep every value short.

Example. Question: can I run my generator in the garage if the door is open
{"standalone_question":"Is it safe to run a portable generator inside a garage with the door open?","intent":"is_it_safe","relation":"portable generator RUNNING INSIDE a garage","not_about":"storing a generator in a garage; garage door openers","keyword_query":"portable generator carbon monoxide enclosed space garage","semantic_query":"carbon monoxide danger from running a generator indoors","alternate":"generator exhaust poisoning indoors ventilation","life_threat":true}

Example. Question: truck just clicks when i turn the key
{"standalone_question":"Why does a vehicle only click and not crank when the key is turned?","intent":"diagnose","relation":"engine DOES NOT CRANK, only a click from the starter","not_about":"clicking noise while driving; key stuck in the ignition","keyword_query":"starter solenoid click no crank weak battery","semantic_query":"causes of a single click and no crank when starting a car","alternate":"car will not turn over battery terminals starter motor","life_threat":false}

Example. Question: pt stung by bee, lips swelling, need tx
{"standalone_question":"How do I treat a person stung by a bee whose lips are swelling?","intent":"treat","relation":"allergic reaction CAUSED BY a bee sting, swelling of the face","not_about":"ordinary bee sting pain; beekeeping","keyword_query":"anaphylaxis insect sting treatment epinephrine","semantic_query":"first aid for a severe allergic reaction to an insect sting","alternate":"bee sting allergy swelling emergency management","life_threat":true}
""".trim()

        /** Reads the model's reply; null when it is not a usable plan (the caller then has no answer). */
        fun parse(json: String): QueryPlan? {
            fun field(name: String): String? = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
                ?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")?.trim()?.takeIf { it.isNotEmpty() }
            val standalone = field("standalone_question") ?: return null
            val keyword = field("keyword_query") ?: return null
            return QueryPlan(standalone, keyword, field("relation").orEmpty(), field("not_about").orEmpty())
        }

        suspend fun make(model: LocalModel, question: String): QueryPlan? =
            runCatching { parse(model.generateJson(SYSTEM, "Question: $question", SCHEMA, MAX_TOKENS)) }.getOrNull()
    }
}
