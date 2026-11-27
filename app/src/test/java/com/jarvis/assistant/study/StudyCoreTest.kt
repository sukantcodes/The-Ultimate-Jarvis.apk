package com.jarvis.assistant.study

import org.junit.Assert.*
import org.junit.Test

class FakeMetrics : TextMetrics {
    override fun width(text: String, size: Float, face: Face): Float = text.length * size * 0.55f
}

class StudyExpressionTest {
    private fun ev(s: String) = StudyExpression.evaluate(s)
    @Test fun basicArithmetic() { assertEquals(5.0, ev("10/2")!!, 1e-9); assertEquals(14.0, ev("2+3*4")!!, 1e-9) }
    @Test fun powersAndBrackets() { assertEquals(64.0, ev("(2+2)^3")!!, 1e-9); assertEquals(-4.0, ev("-2^2")!!, 1e-9) }
    @Test fun latexFractionAndRoot() { assertEquals(0.75, ev("\\frac{3}{4}")!!, 1e-9); assertEquals(5.0, ev("\\sqrt{25}")!!, 1e-9) }
    @Test fun unicodeOperators() { assertEquals(12.0, ev("3 × 4")!!, 1e-9); assertEquals(2.0, ev("8 ÷ 4")!!, 1e-9) }
    @Test fun unknownIsNull() { assertNull(ev("2x + 5")); assertNull(ev("5 m/s")); assertNull(ev("1/0")); assertNull(ev("")) }
    @Test fun variableSampling() { assertEquals(9.0, StudyExpression.evaluate("x^2", mapOf("x" to 3.0), true)!!, 1e-9); assertEquals(6.0, StudyExpression.evaluate("2x", mapOf("x" to 3.0), true)!!, 1e-9) }
    @Test fun deepNestingDoesNotCrash() { assertNull(ev("(".repeat(500) + "1" + ")".repeat(500))) }
}

class StudyVerifierTest {
    @Test fun correctChainPasses() { assertTrue(StudyVerifier.check("x = 10 / 2 = 5") is StudyVerifier.Result.Ok) }
    @Test fun wrongArithmeticIsCaught() {
        val r = StudyVerifier.check("2x = 15 - 5 = 11")
        assertTrue(r is StudyVerifier.Result.Mismatch)
    }
    @Test fun variableSidesAreSkippedNotFlagged() { assertTrue(StudyVerifier.check("2x + 5 = 15") is StudyVerifier.Result.NotApplicable) }
    @Test fun unitsAreSkipped() { assertTrue(StudyVerifier.check("v = 20 m/s") is StudyVerifier.Result.NotApplicable) }
    @Test fun roundedDecimalIsAccepted() { assertTrue(StudyVerifier.check("22 / 7 = 3.14") is StudyVerifier.Result.Ok) }
    @Test fun approxSymbolIsAccepted() { assertTrue(StudyVerifier.check("\\sqrt{2} \\approx 1.41") is StudyVerifier.Result.Ok) }
    @Test fun inequalitiesSkipped() { assertTrue(StudyVerifier.check("3 < 5") is StudyVerifier.Result.NotApplicable) }
    @Test fun arrowsSplitSegments() { assertTrue(StudyVerifier.check("2+3 = 5 \\Rightarrow 5*2 = 10") is StudyVerifier.Result.Ok) }
    @Test fun badSecondSegmentCaught() { assertTrue(StudyVerifier.check("2+3 = 5 \\Rightarrow 5*2 = 12") is StudyVerifier.Result.Mismatch) }
    @Test fun wholeNumberMustBeExact() { assertTrue(StudyVerifier.check("10 / 4 = 3") is StudyVerifier.Result.Mismatch) }
}

class StudyStreamParserTest {
    private fun run(vararg chunks: String): List<StudyEvent> {
        val p = StudyStreamParser(); val out = ArrayList<StudyEvent>()
        for (c in chunks) out.addAll(p.feed(c)); out.addAll(p.finish()); return out
    }
    @Test fun objectsSplitAcrossChunksAreEmittedOnlyWhenComplete() {
        val p = StudyStreamParser()
        assertTrue(p.feed("{\"type\":\"step\",\"content\":\"2x = 10\",\"expl").isEmpty())
        val ev = p.feed("anation\":\"Simplify\"}")
        assertEquals(1, ev.size)
        val s = ev[0] as StudyEvent.Step
        assertEquals("2x = 10", s.content); assertEquals("Simplify", s.explanation)
    }
    @Test fun bracesInsideStringsAreNotStructure() {
        val ev = run("{\"type\":\"step\",\"content\":\"\\\\frac{1}{2} } {\"}")
        assertEquals(1, ev.size); assertTrue(ev[0] is StudyEvent.Step)
    }
    @Test fun codeFencesAndProseAreStray() {
        val ev = run("Sure, here you go:\n```json\n{\"type\":\"final\",\"answer\":\"x = 5\"}\n```")
        assertTrue(ev.any { it is StudyEvent.Stray }); assertTrue(ev.any { it is StudyEvent.Final })
    }
    @Test fun truncatedStreamIsRejectedNotDropped() {
        val ev = run("{\"type\":\"step\",\"content\":\"abc")
        assertTrue(ev.single() is StudyEvent.Rejected)
    }
    @Test fun malformedObjectIsRejected() {
        val ev = run("{\"type\":\"step\",\"content\": }")
        assertTrue(ev.single() is StudyEvent.Rejected)
    }
    @Test fun twoObjectsOnOneLine() { assertEquals(2, run("{\"content\":\"a=1\"}{\"content\":\"b=2\"}").size) }
    @Test fun metaFinalAndDeclined() {
        val ev = run("{\"type\":\"meta\",\"subject\":\"Physics\",\"topic\":\"Kinematics\",\"level\":\"Class 11\"}",
            "{\"type\":\"declined\",\"reason\":\"unclear\"}")
        assertTrue(ev[0] is StudyEvent.Meta); assertTrue(ev[1] is StudyEvent.Declined)
    }
    @Test fun wholeLessonObjectIsExpanded() {
        val ev = run("{\"subject\":\"Mathematics\",\"topic\":\"x\",\"steps\":[{\"content\":\"a = 1\",\"explanation\":\"e\"}],\"final_answer\":\"a = 1\"}")
        assertTrue(ev.first() is StudyEvent.Meta); assertTrue(ev.any { it is StudyEvent.Step }); assertTrue(ev.last() is StudyEvent.Final)
    }
    @Test fun oneCharAtATime() {
        val text = "{\"type\":\"step\",\"content\":\"x = 5\",\"explanation\":\"done\"}"
        val p = StudyStreamParser(); val out = ArrayList<StudyEvent>()
        for (c in text) out.addAll(p.feed(c.toString()))
        assertEquals(1, out.size)
    }
}

class StudyAssemblerTest {
    private fun step(content: String, expl: String = "") = StudyEvent.Step(StepKind.STEP, content, expl, null, "", false)
    private fun asm() = StudyAssembler("2x + 5 = 15", "Class 8")

    @Test fun validStepsAreAddedInOrderAndAnswerCompletes() {
        val a = asm()
        a.accept(StudyEvent.Meta("Mathematics", "Linear Equations", "Class 8"))
        a.accept(step("2x + 5 = 15", "Isolate x."))
        a.accept(step("2x = 15 - 5 = 10", "Subtract 5."))
        a.accept(StudyEvent.Final("x = 5", "", ""))
        val l = a.lesson
        assertEquals(listOf(1, 2), l.steps.map { it.index }); assertTrue(l.complete); assertEquals(StudySubject.MATHEMATICS, l.subject)
        assertEquals(Verification.ARITHMETIC_CHECKED, l.verification)
    }
    @Test fun chatterIsNeverShown() {
        val a = asm()
        val u = a.accept(step("Sure, let's solve this together!"))
        assertTrue(u.isEmpty()); assertTrue(a.lesson.steps.isEmpty())
        assertTrue(StudyAssembler.isChatter("Hope this helps!")); assertFalse(StudyAssembler.isChatter("Subtract 5 from both sides"))
    }
    @Test fun arithmeticErrorStepIsRejectedAndLessonMarkedFailed() {
        val a = asm()
        val u = a.accept(step("2x = 15 - 5 = 11"))
        assertTrue(u.single() is StudyAssembler.Update.StepRejected)
        assertTrue(a.lesson.steps.isEmpty()); assertEquals(Verification.FAILED, a.lesson.verification)
    }
    @Test fun duplicateStepAndRepeatedConclusionAreIgnored() {
        val a = asm()
        a.accept(step("x = 5")); assertTrue(a.accept(step("x = 5")).isEmpty())
        a.accept(StudyEvent.Final("x = 5", "", "")); assertTrue(a.accept(StudyEvent.Final("x = 6", "", "")).isEmpty())
        assertEquals("x = 5", a.lesson.finalAnswer); assertTrue(a.lesson.steps.last().isFinalHighlight)
    }
    @Test fun nothingAfterFinal() { val a = asm(); a.accept(StudyEvent.Final("42", "", "")); assertTrue(a.accept(step("extra = 1")).isEmpty()) }
    @Test fun missingFinalIsNeverInvented() {
        val a = asm(); a.accept(step("x = 5"))
        val u = a.finish(true)
        assertTrue(u.any { it is StudyAssembler.Update.StepRejected }); assertEquals("", a.lesson.finalAnswer); assertFalse(a.lesson.complete)
    }
    @Test fun emptyStreamIsDeclined() { assertTrue(asm().finish(true).single() is StudyAssembler.Update.Declined) }
    @Test fun lowConfidenceIsLabelled() {
        val a = asm(); a.accept(step("x = 5")); a.accept(StudyEvent.Final("x = 5", "", "low"))
        assertEquals(Verification.MODEL_UNCERTAIN, a.lesson.verification)
    }
    @Test fun markdownAndDollarsAreStripped() { assertEquals("x = 5", StudyAssembler.cleanContent("\$\$x = 5\$\$")) }
}

class StudyMathTest {
    private val m = FakeMetrics()
    @Test fun plainEquationHasNoDroppedText() {
        val b = StudyMath.layout("2x + 5 = 15", 30f, Ink.INK, m)
        val text = b.ops.filterIsInstance<DrawOp.Glyphs>().joinToString("") { it.text }
        assertTrue(text.contains("2") && text.contains("x") && text.contains("15") && text.contains("="))
    }
    @Test fun fractionDrawsBarAndBothParts() {
        val b = StudyMath.layout("\\frac{a+b}{c}", 30f, Ink.INK, m)
        assertEquals(1, b.ops.count { it is DrawOp.Line })
        val g = b.ops.filterIsInstance<DrawOp.Glyphs>()
        assertTrue(g.any { it.text == "c" })
    }
    @Test fun superscriptIsSmallerAndRaised() {
        val b = StudyMath.layout("x^2", 30f, Ink.INK, m).ops.filterIsInstance<DrawOp.Glyphs>()
        val base = b.first { it.text == "x" }; val sup = b.first { it.text == "2" }
        assertTrue(sup.size < base.size); assertTrue(sup.y < base.y)
    }
    @Test fun rootAddsRadicalPath() { assertTrue(StudyMath.layout("\\sqrt{x}", 30f, Ink.INK, m).ops.any { it is DrawOp.Poly }) }
    @Test fun chemicalSubscripts() {
        val g = StudyMath.layout("\\ce{H2O}", 30f, Ink.INK, m).ops.filterIsInstance<DrawOp.Glyphs>()
        val two = g.first { it.text == "2" }; val h = g.first { it.text == "H" }
        assertTrue(two.y > h.y && two.size < h.size)
    }
    @Test fun unknownCommandIsKeptAsText() {
        val g = StudyMath.layout("\\weirdthing + 1", 30f, Ink.INK, m).ops.filterIsInstance<DrawOp.Glyphs>().joinToString("") { it.text }
        assertTrue(g.contains("weirdthing"))
    }
    @Test fun malformedLatexNeverThrows() {
        for (s in listOf("\\frac{1", "}}}{{", "x^", "\\sqrt", "_", "\\", "{" .repeat(100) + "a", "\\frac{\\frac{\\frac{a}{b}}{c}}{d}")) StudyMath.layout(s, 30f, Ink.INK, m)
    }
    @Test fun longFormulaFitsWidth() {
        val long = (1..40).joinToString(" + ") { "a$it" }
        val boxes = StudyMath.layoutWrapped(long, 30f, 400f, Ink.INK, m, 8f)
        assertTrue(boxes.all { it.w <= 400f + 1f })
    }
    @Test fun mathVersusProseDetection() {
        assertTrue(StudyMath.looksLikeMath("2x + 5 = 15")); assertTrue(StudyMath.looksLikeMath("F = ma"))
        assertFalse(StudyMath.looksLikeMath("Subtract five from both sides")); assertFalse(StudyMath.looksLikeMath("The mitochondria produce ATP"))
    }
}

class StudyBoardLayoutTest {
    private val m = FakeMetrics()
    private val st = BoardStyle(width = 700f)
    @Test fun wrappingNeverOverflows() {
        val lines = StudyBoardLayout.wrap("word ".repeat(80) + "x".repeat(200), 20f, Face.BODY, 300f, m)
        assertTrue(lines.all { m.width(it, 20f, Face.BODY) <= 300f + 0.01f })
    }
    @Test fun blocksStackWithoutOverlap() {
        val q = StudyBoardLayout.layoutQuestion("2x + 5 = 15", 0f, st, m)
        val s1 = StudyBoardLayout.layoutStep(StudyStep(1, StepKind.STEP, "2x = 10", "Subtract 5 from both sides."), q.bottom, st, m)
        val s2 = StudyBoardLayout.layoutStep(StudyStep(2, StepKind.STEP, "x = 5", ""), s1.bottom, st, m)
        assertTrue(s1.top >= q.bottom && s2.top >= s1.bottom && s2.bottom > s2.top)
    }
    @Test fun notesCanBeHiddenWithoutLosingMaths() {
        val b = StudyBoardLayout.layoutStep(StudyStep(1, StepKind.STEP, "2x = 10", "Subtract 5."), 0f, st, m)
        assertTrue(b.allOps(true).size > b.allOps(false).size); assertTrue(b.ops.isNotEmpty())
    }
    @Test fun finalAnswerIsBoxed() {
        val b = StudyBoardLayout.layoutFinal("x = 5", "", 0f, st, m)
        assertTrue(b.isFinal && b.ops.any { it is DrawOp.Rect })
    }
    @Test fun diagramAndGraphProduceOps() {
        val d = DiagramSpec.parse(com.google.gson.JsonParser.parseString("{\"items\":[{\"t\":\"line\",\"x1\":0,\"y1\":0,\"x2\":50,\"y2\":50,\"arrow\":true},{\"t\":\"label\",\"x\":50,\"y\":60,\"text\":\"F\"}],\"graph\":{\"fn\":\"x^2-4\",\"xmin\":-3,\"xmax\":3}}").asJsonObject)
        assertNotNull(d)
        val r = StudyDiagramLayout.layout(d!!, 500f, 400f, m, 2f)
        assertTrue(r.ops.any { it is DrawOp.Poly } && r.ops.any { it is DrawOp.Glyphs })
    }
    @Test fun emptyDiagramIsNull() { assertNull(DiagramSpec.parse(com.google.gson.JsonParser.parseString("{\"items\":[]}").asJsonObject)) }
}

class StudyPromptTest {
    @Test fun explicitLevelWinsAndAgeMapsToClass() {
        assertTrue(StudyProfile(level = "Class 11").levelHint().contains("Class 11"))
        assertEquals("Class 11", StudyLevels.fromAge("16")); assertEquals("college level", StudyLevels.fromAge("21")); assertNull(StudyLevels.fromAge("abc"))
        assertTrue(StudyProfile(userAge = "16").levelHint().contains("Class 11"))
        assertTrue(StudyProfile().levelHint().contains("never a university derivation"))
    }
    @Test fun systemPromptForbidsChatterAndCarriesLanguage() {
        val s = StudyPrompt.lessonSystem(StudyProfile(language = "Hinglish"))
        assertTrue(s.contains("Never write greetings")); assertTrue(s.contains("Hinglish")); assertFalse(StudyPrompt.lessonUser("2x+5=15").contains("system"))
    }
    @Test fun doubtPromptQuotesBoardAndTappedStep() {
        val lesson = StudyLesson(question = "q", steps = listOf(StudyStep(1, StepKind.STEP, "x = 1", "e")), finalAnswer = "1")
        val u = StudyPrompt.doubtUser(lesson, "why?", 1)
        assertTrue(u.contains("Step 1") && u.contains("tapped step 1") && u.contains("why?"))
    }
    @Test fun extractionParsesAndFlagsUncertainty() {
        val q = StudyExtraction.parse("```json\n{\"questions\":[{\"text\":\"Solve 2x+5=15\",\"confidence\":\"high\",\"has_diagram\":false},{\"text\":\"Find [unclear]\",\"confidence\":\"low\"}]}\n```")
        assertEquals(2, q.size); assertFalse(q[0].needsConfirmation); assertTrue(q[1].needsConfirmation)
        assertTrue(StudyExtraction.parse("I could not read it").isEmpty()); assertTrue(StudyExtraction.parse("{bad").isEmpty())
    }
    @Test fun doubtStepResolution() {
        assertEquals(3, StudyDoubts.resolveStep("why in step 3 did we divide", null, 5))
        assertEquals(2, StudyDoubts.resolveStep("the second step is confusing", null, 5))
        assertEquals(5, StudyDoubts.resolveStep("last step samajh nahi aaya", null, 5))
        assertEquals(4, StudyDoubts.resolveStep("why this?", 4, 5))
        assertEquals(1, StudyDoubts.resolveStep("step 1 why", 4, 5))
        assertNull(StudyDoubts.resolveStep("explain everything", null, 5)); assertNull(StudyDoubts.resolveStep("step 9", null, 5))
    }
}

class StudyPlaybackTest {
    private class Rec : StudyPlayback.Listener {
        val started = ArrayList<Int>(); val finished = ArrayList<Int>(); var all = 0
        override fun onBlockStarted(index: Int, replay: Boolean) { started.add(index) }
        override fun onBlockFinished(index: Int) { finished.add(index) }
        override fun onAllFinished() { all++ }
    }
    private fun pb(n: Int, open: Boolean = false): Pair<StudyPlayback, Rec> {
        val p = StudyPlayback(); val r = Rec(); p.listener = r
        p.setGenerationOpen(open); repeat(n) { p.addBlock(1000) }; return p to r
    }
    private fun run(p: StudyPlayback, ms: Long) { var t = 0L; while (t < ms) { p.advance(50); t += 50 } }

    @Test fun playsBlocksInOrderThenFinishes() {
        val (p, r) = pb(3); run(p, 10_000)
        assertEquals(listOf(0, 1, 2), r.started); assertEquals(listOf(0, 1, 2), r.finished); assertEquals(StudyPlayback.State.FINISHED, p.state); assertEquals(1, r.all)
    }
    @Test fun pauseFreezesAndResumeContinues() {
        val (p, r) = pb(2); run(p, 400); p.pause(); val at = p.progress; run(p, 5000)
        assertEquals(at, p.progress, 0f); assertEquals(1, r.started.size); p.resume(); run(p, 5000); assertEquals(StudyPlayback.State.FINISHED, p.state)
    }
    @Test fun waitsForGeneratedContentThenWritesIt() {
        val (p, r) = pb(1, open = true); run(p, 3000)
        assertEquals(StudyPlayback.State.WAITING_FOR_CONTENT, p.state)
        p.addBlock(800); assertEquals(listOf(0, 1), r.started); run(p, 3000); assertEquals(StudyPlayback.State.WAITING_FOR_CONTENT, p.state)
        p.setGenerationOpen(false); assertEquals(StudyPlayback.State.FINISHED, p.state)
    }
    @Test fun narrationHoldsTheNextBlock() {
        val (p, r) = pb(2); p.narrationBusy = true; run(p, 4000)
        assertEquals(listOf(0), r.started); p.narrationBusy = false; run(p, 200); assertEquals(listOf(0, 1), r.started)
    }
    @Test fun previousNextAndReplay() {
        val (p, r) = pb(3); run(p, 1500); p.next(); assertEquals(2, p.current)
        p.previous(); assertEquals(1, p.current)
        p.replayCurrent(); assertEquals(1, p.current); run(p, 3000); assertEquals(StudyPlayback.State.PAUSED, p.state) // stops after the replayed step
        p.replayAll(); assertEquals(0, p.current)
    }
    @Test fun speedChangesDurationButNeverBelowLimits() {
        val (p, _) = pb(1); p.speed = 100f; assertEquals(3f, p.speed, 0f); p.speed = 0f; assertEquals(0.4f, p.speed, 0f)
    }
    @Test fun clearResets() { val (p, _) = pb(2); run(p, 500); p.clear(); assertEquals(0, p.blockCount); assertEquals(StudyPlayback.State.IDLE, p.state) }
}

class StudyCodecTest {
    @Test fun roundTripKeepsEverythingIncludingDiagrams() {
        val d = com.google.gson.JsonParser.parseString("{\"items\":[{\"t\":\"circle\",\"cx\":50,\"cy\":50,\"r\":20}]}").asJsonObject
        val step = StudyStep(1, StepKind.DIAGRAM, "", "A circle", DiagramSpec.parse(d), d.toString())
        val lesson = StudyLesson(StudySubject.BIOLOGY, "Cell", "Draw a cell", "Class 9", listOf(step, StudyStep(2, StepKind.STEP, "a = 1", "e", check = StepCheck.ARITHMETIC_OK)), "a = 1", "", Verification.ARITHMETIC_CHECKED, true)
        val doubt = StudyDoubt("why?", 2, listOf(StudyStep(1, StepKind.STEP, "b", "")), 123L)
        val back = StudyLessonCodec.fromJson(StudyLessonCodec.toJson(lesson, listOf(doubt), setOf(2)))!!
        assertEquals(lesson.question, back.lesson.question); assertEquals(2, back.lesson.steps.size); assertNotNull(back.lesson.steps[0].diagram)
        assertEquals(StepCheck.ARITHMETIC_OK, back.lesson.steps[1].check); assertEquals(1, back.doubts.size); assertEquals(setOf(2), back.bookmarks); assertTrue(back.lesson.complete)
    }
    @Test fun damagedOrFutureSavesAreRejected() {
        assertNull(StudyLessonCodec.fromJson("not json")); assertNull(StudyLessonCodec.fromJson("{\"v\":99,\"steps\":[]}")); assertNull(StudyLessonCodec.fromJson("{\"v\":1}"))
    }
}

class StudySpeechTextTest {
    @Test fun readsEquationsAloud() {
        assertEquals("x squared equals 4", StudySpeechText.verbalize("x^{2} = 4"))
        assertEquals("a over b plus 3", StudySpeechText.verbalize("\\frac{a}{b} + 3"))
        assertEquals("x equals 10 divided by 2 equals 5", StudySpeechText.verbalize("x = 10 / 2 = 5"))
        assertEquals("v equals 20 minus 5", StudySpeechText.verbalize("v = 20 - 5"))
        assertEquals("square root of 25 equals 5", StudySpeechText.verbalize("\\sqrt{25} = 5"))
    }
    @Test fun noLatexLeaksIntoSpeech() {
        val s = StudySpeechText.verbalize("\\Delta E = mc^{2} \\times \\frac{1}{2}")
        assertFalse(s.contains("\\") || s.contains("{") || s.contains("^"))
    }
    @Test fun hindiAndHinglishWords() {
        assertTrue(StudySpeechText.verbalize("2 + 3 = 5", "hindi").contains("जमा"))
        assertTrue(StudySpeechText.verbalize("2 x 3", "hinglish").isNotEmpty()); assertEquals("Answer hai", StudySpeechText.answerPrefix("Hinglish"))
    }
}

class StudyPlaybackVisibilityTest {
    @Test fun replayingOneStepKeepsTheRestVisibleAndReplayAllHidesThem() {
        val p = StudyPlayback(); p.setGenerationOpen(false); repeat(4) { p.addBlock(500) }
        var t = 0L; while (t < 10_000) { p.advance(50); t += 50 }
        assertEquals(3, p.highWater)
        p.seek(1); assertTrue(p.othersStayVisible); assertEquals(1, p.current)
        t = 0; while (t < 3_000) { p.advance(50); t += 50 }
        assertEquals(StudyPlayback.State.PAUSED, p.state); assertEquals(3, p.highWater)
        p.replayAll(); assertFalse(p.othersStayVisible); assertEquals(0, p.current)
    }
}

class StudyPlaybackRestoreTest {
    @Test fun jumpToEndShowsEverythingAndTruncateKeepsStateValid() {
        val p = StudyPlayback(); p.setGenerationOpen(false); repeat(5) { p.addBlock(500) }
        p.jumpToEnd(); assertEquals(StudyPlayback.State.FINISHED, p.state); assertEquals(4, p.current); assertEquals(4, p.highWater)
        p.truncateTo(3); assertEquals(3, p.blockCount); assertEquals(2, p.current); assertTrue(p.highWater <= 2)
        p.truncateTo(0); assertEquals(StudyPlayback.State.IDLE, p.state)
    }
    @Test fun doubtHeaderUsesItsOwnPrefix() {
        val b = StudyBoardLayout.layoutQuestion("why divide by 2?", 0f, BoardStyle(700f), FakeMetrics(), prefix = "?")
        assertTrue(b.ops.filterIsInstance<DrawOp.Glyphs>().any { it.text == "?" })
    }
}

class StudyTeacherVoiceTest {
    @Test fun sayFieldIsParsedAndCleanedForSpeech() {
        val p = StudyStreamParser()
        val ev = p.feed("{\"type\":\"step\",\"content\":\"2x = 10\",\"say\":\"Now we divide **both** sides by 2, so x \\\\frac{1}{2}.\"}\n")
        val st = ev.filterIsInstance<StudyEvent.Step>().single()
        val a = StudyAssembler("q", "Class 8"); a.accept(StudyEvent.Meta("Mathematics", "t", "")); a.accept(st)
        val spoken = a.lesson.steps.single().spoken
        assertTrue(spoken.startsWith("Now we divide both sides by 2")); assertFalse(spoken.contains("\\")); assertFalse(spoken.contains("*"))
    }
    @Test fun chatbotFillerNeverReachesTheSpeaker() {
        assertEquals("", StudyAssembler.cleanSpoken("Hope this helps, feel free to ask more!"))
        assertEquals("", StudyAssembler.cleanSpoken("As an AI I cannot say."))
        assertEquals("Ab hum dono taraf 5 ghataenge.", StudyAssembler.cleanSpoken("Ab hum dono taraf 5 ghataenge."))
    }
    @Test fun finalSayIsKept() {
        val a = StudyAssembler("q", ""); a.accept(StudyEvent.Meta("Mathematics", "t", ""))
        a.accept(StudyEvent.Step(StepKind.STEP, "x = 5", "", null, "", false))
        a.accept(StudyEvent.Final("y = 7", "", "high", "So y is seven."))
        assertEquals("So y is seven.", a.lesson.finalSpoken)
    }
    @Test fun spokenSurvivesSaveAndOpen() {
        val l = StudyLesson(StudySubject.MATHEMATICS, "t", "q", "", listOf(StudyStep(1, StepKind.STEP, "x = 1", "", spoken = "Bas yahi hai.")), "x = 1", "", Verification.NOT_VERIFIED, true, finalSpoken = "Done.")
        val back = StudyLessonCodec.fromJson(StudyLessonCodec.toJson(l, emptyList(), emptySet()))!!
        assertEquals("Bas yahi hai.", back.lesson.steps[0].spoken); assertEquals("Done.", back.lesson.finalSpoken)
    }
    @Test fun gateHoldsThePenUntilTheVoiceIsReady() {
        val p = StudyPlayback(); p.setGenerationOpen(true)
        p.listener = object : StudyPlayback.Listener { override fun onBlockStarted(index: Int, replay: Boolean) { p.startGate = true } }
        p.addBlock(1000)
        repeat(40) { p.advance(50) }
        assertEquals(0f, p.progress, 0f)
        p.startGate = false
        repeat(10) { p.advance(50) }
        assertTrue(p.progress > 0.3f)
    }
    @Test fun promptAsksForSpokenLineAndAnyLanguage() {
        val s = StudyPrompt.lessonSystem(StudyProfile(language = "Bengali"))
        assertTrue(s.contains("\"say\"")); assertTrue(s.contains("Bengali"))
        assertTrue(StudyLevels.LANGUAGES.size > 10)
    }
}

class StudyMemoryTest {
    private fun t(q: String, a: String = "a", k: StudyMemory.Kind = StudyMemory.Kind.LESSON) = StudyMemory.Turn(k, "Mathematics", "Algebra", q, a, "", 1L)
    @Test fun emptyMemoryAddsNothingToThePrompt() {
        assertEquals("", StudyMemory().block()); assertEquals("Question:\n2+2", StudyPrompt.lessonUser("2+2", StudyMemory().block()))
    }
    @Test fun remembersInOrderAndReachesThePrompt() {
        val m = StudyMemory(); m.add(t("solve 2x+5=15", "x = 5")); m.add(t("why subtract 5?", "to isolate 2x", StudyMemory.Kind.DOUBT))
        val b = m.block()
        assertTrue(b.indexOf("solve 2x+5=15") < b.indexOf("why subtract 5?")); assertTrue(b.contains("x = 5"))
        assertTrue(StudyPrompt.lessonUser("now with 7", b).endsWith("Question:\nnow with 7"))
    }
    @Test fun oldestTurnsAreForgottenAndRepeatsReplaceOlderOnes() {
        val m = StudyMemory(maxTurns = 3)
        for (i in 1..5) m.add(t("q$i"))
        assertEquals(listOf("q3", "q4", "q5"), m.all().map { it.question })
        m.add(t("Q4  ")); assertEquals(3, m.size); assertEquals("Q4", m.last()!!.question)
    }
    @Test fun blockStaysWithinItsBudget() {
        val m = StudyMemory(maxTurns = 8, maxChars = 600)
        for (i in 1..8) m.add(t("question number $i " + "x".repeat(300), "answer ".repeat(30)))
        assertTrue(m.block().length < 600 + 700)
        assertTrue(m.block().contains("question number 8"))
    }
    @Test fun saveAndLoadRoundTripAndBadDataIsIgnored() {
        val m = StudyMemory(); m.add(t("q1", "a1"))
        val n = StudyMemory(); n.load(m.toJson()); assertEquals("q1", n.last()!!.question)
        n.load("garbage"); assertTrue(n.isEmpty); n.load("{\"v\":7,\"turns\":[]}"); assertTrue(n.isEmpty)
    }
    @Test fun turnsFromLessonKeepFormulasAndAnswer() {
        val l = StudyLesson(StudySubject.PHYSICS, "Ohm", "find I", "", listOf(StudyStep(1, StepKind.FORMULA, "V = IR", "")), "I = 2 A", "", Verification.NOT_VERIFIED, true)
        val turn = StudyMemory.turnFrom(l, 5L)
        assertTrue(turn.keyPoints.contains("V = IR")); assertEquals("I = 2 A", turn.answer)
    }
}

class StudyVoiceCodecTest {
    @Test fun rawPcmGetsAValidWavHeader() {
        val pcm = ByteArray(480) { it.toByte() }
        val wav = com.jarvis.assistant.study.voice.StudyVoiceCodec.pcm16ToWav(pcm, 24000)
        assertEquals(44 + 480, wav.size)
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4)); assertEquals("data", String(wav, 36, 4))
        fun le(o: Int) = (wav[o].toInt() and 0xFF) or ((wav[o + 1].toInt() and 0xFF) shl 8) or ((wav[o + 2].toInt() and 0xFF) shl 16) or ((wav[o + 3].toInt() and 0xFF) shl 24)
        assertEquals(24000, le(24)); assertEquals(48000, le(28)); assertEquals(480, le(40)); assertEquals(36 + 480, le(4))
    }
    @Test fun mimeTypesAreRecognised() {
        val c = com.jarvis.assistant.study.voice.StudyVoiceCodec
        assertTrue(c.isRawPcm("audio/L16;codec=pcm;rate=24000")); assertFalse(c.isRawPcm("audio/mpeg")); assertFalse(c.isRawPcm("audio/wav"))
        assertEquals(24000, c.sampleRate("audio/L16;codec=pcm;rate=24000")); assertEquals(16000, c.sampleRate("audio/pcm;rate=16000")); assertEquals(24000, c.sampleRate("audio/L16"))
    }
    @Test fun speechModelIsChosenFromTheListNeverGuessed() {
        val c = com.jarvis.assistant.study.voice.StudyVoiceCodec
        val m = listOf(
            com.jarvis.assistant.study.voice.StudyVoiceCodec.ModelEntry("models/gemini-2.5-flash", listOf("generateContent")),
            com.jarvis.assistant.study.voice.StudyVoiceCodec.ModelEntry("models/gemini-2.5-flash-preview-tts", listOf("generateContent")),
            com.jarvis.assistant.study.voice.StudyVoiceCodec.ModelEntry("models/gemini-3.8-flash-tts", listOf("generateContent")),
            com.jarvis.assistant.study.voice.StudyVoiceCodec.ModelEntry("models/gemini-3.8-flash-live-tts", listOf("generateContent")),
            com.jarvis.assistant.study.voice.StudyVoiceCodec.ModelEntry("models/gemini-3.9-pro-tts", listOf("embedContent"))
        )
        assertEquals("gemini-3.8-flash-tts", c.pickTtsModel(m))
        assertNull(c.pickTtsModel(listOf(com.jarvis.assistant.study.voice.StudyVoiceCodec.ModelEntry("models/gemini-2.5-flash", listOf("generateContent")))))
        assertNull(c.pickTtsModel(emptyList()))
    }
}

class StudyVoiceLanguageTest {
    private val c = com.jarvis.assistant.study.voice.StudyVoiceCodec
    @Test fun namedLanguagesMapToTheirVoices() {
        assertEquals("bn-IN", c.languageTag("Bengali", "x")); assertEquals("hi-IN", c.languageTag("Hindi", "x")); assertEquals("en-IN", c.languageTag("Hinglish", "kaise ho"))
    }
    @Test fun autoReadsTheScriptOfTheText() {
        assertEquals("bn-IN", c.languageTag("Auto", "আমরা দুই পাশ থেকে ৫ বিয়োগ করব")); assertEquals("hi-IN", c.languageTag("auto", "दोनों तरफ से 5 घटाएँ"))
        assertEquals("ta-IN", c.languageTag("Auto", "இரு பக்கமும்")); assertEquals("en-IN", c.languageTag("Auto", "Subtract 5 from both sides"))
    }
}
class StudyMemoryExclusionTest {
    @Test fun thePresentLessonIsNotListedTwice() {
        val m = StudyMemory()
        m.add(StudyMemory.Turn(StudyMemory.Kind.LESSON, "Mathematics", "", "solve x+1=2", "x = 1", "", 1L))
        m.add(StudyMemory.Turn(StudyMemory.Kind.LESSON, "Mathematics", "", "solve y+2=5", "y = 3", "", 2L))
        val b = m.block("solve y+2=5")
        assertTrue(b.contains("solve x+1=2")); assertFalse(b.contains("y+2=5"))
        assertEquals("", StudyMemory().also { it.add(StudyMemory.Turn(StudyMemory.Kind.LESSON, "", "", "q", "", "", 1L)) }.block("q"))
    }
}

class StudyBoardRichDrawingAndLayoutTest {
    private val m = FakeMetrics()
    private val st = BoardStyle(width = 800f, baseSize = 32f, margin = 30f)

    @Test fun richDiagramOrdersOutlinesThenFillsThenCalloutLabels() {
        val raw = """
            {
              "title": "Human Heart Cross Section",
              "items": [
                {"t":"curve","pts":[[30,20],[70,20],[78,60],[50,86],[22,60]],"closed":true,"smooth":true,"fill":true,"color":"ink","fillColor":"rose","shade":"radial","layer":"outline"},
                {"t":"oval","cx":42,"cy":40,"rx":10,"ry":12,"fill":true,"color":"warn","fillColor":"red","layer":"outline"},
                {"t":"arc","cx":58,"cy":42,"rx":12,"ry":12,"start":0,"sweep":180,"fill":false,"color":"teal","layer":"detail"},
                {"t":"label","ax":42,"ay":40,"x":8,"y":30,"text":"Right Atrium","color":"violet"}
              ]
            }
        """.trimIndent()
        val spec = DiagramSpec.parse(com.google.gson.JsonParser.parseString(raw).asJsonObject)
        assertNotNull(spec)
        val res = StudyDiagramLayout.layout(spec!!, 600f, 500f, m, 2.5f)
        val firstStrokeIdx = res.ops.indexOfFirst { it is DrawOp.Poly && it.paintMode == PaintMode.STROKE_ONLY && it.smooth }
        val firstFillIdx = res.ops.indexOfFirst { it.paintModeOrNull() == PaintMode.FILL_ONLY }
        val labelIdx = res.ops.indexOfFirst { it is DrawOp.Glyphs && it.text == "Right Atrium" }
        assertTrue(firstStrokeIdx in 0 until firstFillIdx)
        assertTrue(firstFillIdx in 0 until labelIdx)
    }

    private fun DrawOp.paintModeOrNull(): PaintMode? = when (this) {
        is DrawOp.Poly -> paintMode
        is DrawOp.Oval -> paintMode
        is DrawOp.Rect -> paintMode
        is DrawOp.Arc -> paintMode
        else -> null
    }

    @Test fun oversizedDiagramAndEdgeLabelsStayInsideVisibleBoardBounds() {
        val raw = """
            {
              "title": "Extreme Bounds Check",
              "items": [
                {"t":"circle","cx":115,"cy":50,"r":28,"fill":true,"color":"blue"},
                {"t":"rect","x":-25,"y":10,"w":40,"h":30,"fill":true,"color":"gold","rx":4},
                {"t":"label","x":99,"y":50,"text":"Very Long Right Overflow Label Check","color":"warn"},
                {"t":"label","x":1,"y":52,"text":"Very Long Left Label","color":"teal"}
              ]
            }
        """.trimIndent()
        val spec = DiagramSpec.parse(com.google.gson.JsonParser.parseString(raw).asJsonObject)!!
        val res = StudyDiagramLayout.layout(spec, 500f, 400f, m, 2f)
        val bounds = DrawBounds.measure(res.ops, m)!!
        assertTrue("minX=${bounds.minX} must be >= 0", bounds.minX >= 0f)
        assertTrue("maxX=${bounds.maxX} must be <= 500", bounds.maxX <= 500.5f)
        assertTrue("maxY=${bounds.maxY} must be <= 400", bounds.maxY <= 400.5f)
    }

    @Test fun markdownCodeNoiseIsStrippedFromNonCodeSteps() {
        val raw = "### Key Concept\n- **Chlorophyll**: Absorbs `light energy` and drives *photosynthesis*."
        val cleaned = StudyAssembler.cleanContent(raw)
        assertFalse(cleaned.contains("###"))
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("`"))
        assertFalse(cleaned.contains("*"))
        assertTrue(cleaned.contains("• Chlorophyll: Absorbs light energy and drives photosynthesis."))
    }

    @Test fun proseWithInlineMathRendersMultiColorAndNeverSplitsEnglishWords() {
        val step = StudyStep(
            1, StepKind.DEFINITION,
            "Photosynthesis: Converts 6CO_{2} + 6H_{2}O into glucose and releases \\frac{1}{2}O_{2} energy.",
            "Note why H_{2}O splits under light."
        )
        assertFalse(StudyMath.looksLikeMath(step.content))
        val block = StudyBoardLayout.layoutStep(step, 0f, st, m)
        val glyphs = block.ops.filterIsInstance<DrawOp.Glyphs>()
        val inksUsed = block.ops.map { it.ink }.toSet()
        assertTrue("Multiple colours should appear on board answers", inksUsed.size >= 3)
        // Prose words must stay whole words (never split into 'o' 'f' italic symbols)
        assertTrue(glyphs.any { it.text.contains("Converts") && it.face != Face.MATH_ITALIC && it.face != Face.CODE })
        // Raw LaTeX commands like \frac must not leak literally
        assertFalse(glyphs.any { it.text.contains("\\frac") })
        val bounds = DrawBounds.measure(block.allOps(true), m)!!
        assertTrue("Block width must stay inside board margins", bounds.minX >= 0f && bounds.maxX <= st.width + 0.5f)
    }

    @Test fun humanBrainAtlasRendersDetailedLobesWithZeroOverlappingLabels() {
        val brain = StudyDiagramAtlas.match("Draw a labelled diagram of the human brain")!!
        assertTrue(brain.items.count { it is DiagramSpec.Item.Label } >= 10)
        assertTrue(brain.items.any { it is DiagramSpec.Item.Poly && it.smooth && it.fill })
        val labelNames = brain.items.filterIsInstance<DiagramSpec.Item.Label>().map { it.text }
        assertTrue("Frontal Lobe" in labelNames && "Cerebellum" in labelNames && "Medulla Oblongata" in labelNames)

        val laid = StudyDiagramLayout.layout(brain, 940f, 760f, m, 2.4f)
        val labelBoxes = laid.ops.filterIsInstance<DrawOp.Rect>().filter { kotlin.math.abs(it.rx - 10f) < 0.2f }
        assertEquals(labelNames.size, labelBoxes.size)

        // Verify zero collisions across every pair of callout pills
        for (i in labelBoxes.indices) {
            for (j in i + 1 until labelBoxes.size) {
                val a = labelBoxes[i]
                val b = labelBoxes[j]
                val horizOverlap = a.x < b.x + b.w && a.x + a.w > b.x
                val vertOverlap = a.y < b.y + b.h + 1f && a.y + a.h + 1f > b.y
                assertFalse("Labels $i and $j overlapped: (${a.x},${a.y},${a.w},${a.h}) vs (${b.x},${b.y},${b.w},${b.h})", horizOverlap && vertOverlap)
            }
        }
        val bounds = DrawBounds.measure(laid.ops, m)!!
        assertTrue("minX=${bounds.minX} >= 0 && maxX=${bounds.maxX} <= 940", bounds.minX >= 0f && bounds.maxX <= 940.5f)
        assertTrue("maxY=${bounds.maxY} <= 760", bounds.maxY <= 760.5f)
    }

    @Test fun canonicalAtlasCoversCoreStemSubjectsAndEnhancesAssembler() {
        for (q in listOf(
            "Draw human heart diagram",
            "Explain human eye structure",
            "Structure of neuron",
            "Plant cell diagram",
            "Animal cell diagram",
            "Mitochondria organelle diagram",
            "Chloroplast structure diagram",
            "Human digestive system diagram",
            "Human ear anatomy diagram",
            "Reflex arc spinal cord diagram",
            "Human kidney section",
            "Nephron structure",
            "Human respiratory system",
            "Leaf stomata cross section",
            "DNA double helix",
            "Longitudinal section of flower",
            "Refraction through glass slab",
            "Dispersion of light through glass prism",
            "Ray diagram of concave lens",
            "Ray diagram of convex lens",
            "Ray diagram of concave mirror",
            "AC generator diagram",
            "Solenoid magnetic field diagram",
            "Electric circuit diagram",
            "Magnetic field lines of bar magnet",
            "Transverse wave diagram",
            "Inclined plane free body diagram",
            "Simple pendulum diagram",
            "DC motor diagram",
            "Benzene ring C6H6",
            "Water molecule H2O structure",
            "Simple distillation apparatus",
            "Electrolysis cell diagram",
            "pH scale chart",
            "Bohr model of atom",
            "Pythagoras theorem squares",
            "Right triangle trigonometry",
            "Parts of a circle",
            "Cone slant height geometry",
            "Cylinder geometry",
            "Venn diagram sets",
            "Binary tree data structure",
            "Linked list data structure",
            "Stack and queue LIFO FIFO",
            "OSI model 7 layers",
            "Von Neumann CPU architecture",
            "Neural network perceptron",
            "Logic gates AND OR NOT",
            "Solar system orbits diagram",
            "Carbon cycle diagram",
            "Layers of the earth",
            "Water cycle diagram"
        )) {
            val spec = StudyDiagramAtlas.match(q)
            assertNotNull("Missing canonical blueprint for: $q", spec)
            val laid = StudyDiagramLayout.layout(spec!!, 920f, 740f, m, 2.4f)
            assertTrue("Expected non-empty layout for: $q", laid.ops.isNotEmpty())
            val labelBoxes = laid.ops.filterIsInstance<DrawOp.Rect>().filter {
                it.paintMode == PaintMode.FILL_AND_STROKE && kotlin.math.abs(it.fillAlpha - 0.12f) < 0.01f
            }
            assertEquals("Expected all callout labels for: $q", spec.items.count { it is DiagramSpec.Item.Label }, labelBoxes.size)
            for (i in labelBoxes.indices) {
                for (j in i + 1 until labelBoxes.size) {
                    val a = labelBoxes[i]
                    val b = labelBoxes[j]
                    val overlapX = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
                    val overlapY = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
                    assertFalse("Labels overlap for '$q': $a vs $b", overlapX > 0.5f && overlapY > 0.5f)
                }
            }
        }

        val asm = StudyAssembler("Draw a neat labelled diagram of brain", "Class 10")
        asm.accept(StudyEvent.Meta("Biology", "Control and Coordination", "Class 10"))
        asm.accept(StudyEvent.Step(StepKind.STEP, "The brain coordinates voluntary and involuntary actions.", "", null, "", true))
        val updates = asm.accept(StudyEvent.Final("Human brain consists of forebrain, midbrain, and hindbrain.", "", "high"))
        assertTrue(updates.any { it is StudyAssembler.Update.StepAdded })
        assertTrue(asm.lesson.steps.any { it.diagram?.title?.contains("Brain", ignoreCase = true) == true })
    }

    @Test fun omittedAnchorLabelsSnapOntoShapesWithoutOverlap() {
        val raw = """
            {
              "title": "Pinless Labels Test",
              "items": [
                {"t": "circle", "cx": 150, "cy": 100, "r": 50, "ink": "accent", "fill": "soft"},
                {"t": "rect", "x": 115, "y": 135, "w": 70, "h": 26, "ink": "teal", "fill": "soft"},
                {"t": "label", "x": 135, "y": 92, "text": "Upper Hemisphere Region"},
                {"t": "label", "x": 136, "y": 95, "text": "Inner Core Ring"},
                {"t": "label", "x": 145, "y": 142, "text": "Base Rect Plate"}
              ]
            }
        """.trimIndent()
        val spec = DiagramSpec.parse(com.google.gson.JsonParser.parseString(raw).asJsonObject)
        assertNotNull(spec)
        val laid = StudyDiagramLayout.layout(spec!!, 820f, 520f, m, 2.2f)
        val labelBoxes = laid.ops.filterIsInstance<DrawOp.Rect>().filter {
            it.paintMode == PaintMode.FILL_AND_STROKE && kotlin.math.abs(it.fillAlpha - 0.12f) < 0.01f
        }
        assertEquals(3, labelBoxes.size)
        for (i in labelBoxes.indices) {
            for (j in i + 1 until labelBoxes.size) {
                val a = labelBoxes[i]
                val b = labelBoxes[j]
                val overlapX = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
                val overlapY = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
                assertFalse("Omitted-pin labels collapsed/overlapped: $a vs $b", overlapX > 0.5f && overlapY > 0.5f)
            }
        }
        val pins = laid.ops.filterIsInstance<DrawOp.Oval>().filter { kotlin.math.abs(it.rx - it.ry) < 0.1f && it.rx in 2.5f..8f }
        assertTrue("Expected snapped anchor dots on shapes", pins.isNotEmpty())
    }

    @Test fun canonicalShortcutAndShapeAliasesParseFastAndCleanly() {
        val parser = StudyStreamParser()
        val asm = StudyAssembler("Draw a mitochondria diagram", "Class 10")
        val line1 = """{"type":"meta","subject":"Biology","topic":"Cell Organelles","level":"Class 10"}"""
        val line2 = """{"type":"diagram","explanation":"Powerhouse of the cell.","diagram":{"title":"Mitochondria","canonical":true}}"""
        for (ev in parser.feed("$line1\n$line2\n")) asm.accept(ev)
        assertEquals(1, asm.lesson.steps.size)
        val diag = asm.lesson.steps.first().diagram
        assertNotNull(diag)
        assertTrue(diag!!.title.contains("Mitochondrion", ignoreCase = true))
        assertTrue(diag.items.size >= 15)

        val aliasSpec = DiagramSpec.parse(
            com.google.gson.JsonParser.parseString(
                """{"title":"Alias Check","items":[{"t":"oval","cx":50,"cy":50,"w":30,"h":18,"fill":true},{"t":"circle","x":50,"y":50,"d":12},{"t":"rect","cx":50,"cy":50,"w":20,"h":10},{"t":"poly","coords":[[20,20],[80,20],[50,80]],"closed":true,"fill":true}]}"""
            ).asJsonObject
        )
        assertNotNull(aliasSpec)
        assertEquals(4, aliasSpec!!.items.size)
    }
}

class StudyControllerAndVoiceUpgradeTest {
    @Test fun controllerTracksAudioSuppressionAndBuffersCommandsUntilAttached() {
        StudyBoardController.resetForTest()
        assertFalse(StudyBoardController.isAudioSuppressionActive())

        StudyBoardController.setNarrating(true)
        assertTrue(StudyBoardController.isAudioSuppressionActive())
        StudyBoardController.setNarrating(false)
        StudyBoardController.setMicInputActive(true)
        assertTrue(StudyBoardController.isAudioSuppressionActive())
        StudyBoardController.setMicInputActive(false)
        assertFalse(StudyBoardController.isAudioSuppressionActive())

        val cmd1 = StudyBoardController.StudyBoardCommand.OpenAndExplain("Solve 2x + 5 = 15", mode = "new")
        val cmd2 = StudyBoardController.StudyBoardCommand.SetSpeed(speed = 1.5f)
        StudyBoardController.dispatchCommand(cmd1)
        StudyBoardController.dispatchCommand(cmd2)

        val drained = StudyBoardController.attachReceiver()
        assertEquals(listOf(cmd1, cmd2), drained)
        assertTrue(StudyBoardController.attachReceiver().isEmpty())
        StudyBoardController.resetForTest()
    }

    @Test fun cleanSpokenPreservesDetailedExplanationsWithoutTruncatingAt420Chars() {
        val longText = "Step one explains conservation of momentum where total initial momentum equals total final momentum across the system. ".repeat(6).trim()
        assertTrue(longText.length > 600)
        val cleaned = StudyAssembler.cleanSpoken(longText)
        assertEquals(longText, cleaned)
        assertTrue(StudyPrompt.OUTPUT_RULES.contains("EVERY step MUST include \"say\""))
    }

    @Test fun multilingualVoiceCodecAndSpeechTextCoverIndianAndWorldLanguages() {
        val c = com.jarvis.assistant.study.voice.StudyVoiceCodec
        assertEquals("hi-IN", c.languageTag("Bhojpuri", "test"))
        assertEquals("te-IN", c.languageTag("Telugu", "test"))
        assertEquals("ta-IN", c.languageTag("Tamil", "test"))
        assertEquals("te-IN", c.languageTag("Auto", "న్యూటన్ మూడవ నియమం"))
        assertEquals("Telugu", c.resolveLanguageName("Auto", "న్యూటన్ మూడవ నియమం"))
        assertEquals("Bengali", c.resolveLanguageName("Auto", "আমরা দুই পাশ থেকে যোগ করব"))
        assertEquals("Japanese", c.resolveLanguageName("Auto", "これはテストです"))
        assertTrue(StudySpeechText.verbalize("2 + 3 = 5", "bengali").contains("যোগ"))
        assertTrue(StudySpeechText.verbalize("2 + 3 = 5", "telugu").contains("సమానం"))
        assertTrue(StudySpeechText.verbalize("2 + 3 = 5", "tamil").contains("கூட்டல்"))
    }

    @Test fun commandParserRoutesStudyBoardOpenExplainAndControls() {
        val cp = com.jarvis.assistant.automation.command.CommandParser

        val openOnly = cp.parse("open study board", studyBoardActive = false).command
            as com.jarvis.assistant.automation.command.JarvisCommand.StudyBoardAction
        assertEquals(StudyTools.CONTROL, openOnly.toolName)
        assertEquals("open", openOnly.action)

        val openExplain = cp.parse("Hey Jarvis, open study board and explain Newton's Third Law", studyBoardActive = false).command
            as com.jarvis.assistant.automation.command.JarvisCommand.StudyBoardAction
        assertEquals(StudyTools.EXPLAIN, openExplain.toolName)
        assertEquals("Newton's Third Law", openExplain.query)

        val explainMe = cp.parse("Jarvis explain me Pythagoras theorem with diagram", studyBoardActive = false).command
            as com.jarvis.assistant.automation.command.JarvisCommand.StudyBoardAction
        assertEquals(StudyTools.EXPLAIN, explainMe.toolName)
        assertEquals("Pythagoras theorem with diagram", explainMe.query)

        // Ambiguous "explain me this" should NOT bypass live AI vision/context
        assertNull(cp.parse("explain me this", studyBoardActive = false).command)

        // Contextual commands only hijack generic verbs when Study Board is visible
        assertNull(cp.parse("next step", studyBoardActive = false).command)
        val nextActive = cp.parse("next step", studyBoardActive = true).command
            as com.jarvis.assistant.automation.command.JarvisCommand.StudyBoardAction
        assertEquals("next", nextActive.action)

        val stepSelect = cp.parse("study board go to step 3", studyBoardActive = false).command
            as com.jarvis.assistant.automation.command.JarvisCommand.StudyBoardAction
        assertEquals("select_step", stepSelect.action)
        assertEquals(3, stepSelect.stepNumber)

        val speedSet = cp.parse("study board set speed to 1.5x", studyBoardActive = false).command
            as com.jarvis.assistant.automation.command.JarvisCommand.StudyBoardAction
        assertEquals("set_speed", speedSet.action)
        assertEquals("1.5", speedSet.value)

        assertTrue(StudyTools.EXPLAIN in StudyTools.NAMES)
        assertTrue(StudyTools.CONTROL in StudyTools.NAMES)
    }

    @Test fun continuousNarrationPlanAndPlaybackAdvanceSmoothlyWithoutStops() {
        val c = com.jarvis.assistant.study.voice.StudyVoiceCodec
        assertEquals("models/gemini-3.8-live", c.normalizeLiveModelId("models/gemini-3.8-live"))
        assertEquals("models/gemini-3.1-flash-live-preview", c.normalizeLiveModelId("models/gemini-2.5-flash"))
        assertEquals("First step.", c.ensureSentenceEnding("First step"))

        val plan = c.buildContinuousPlan(
            listOf(
                1 to "First write down what is given in the triangle",
                2 to "Apply Pythagoras theorem to relate all sides cleanly",
                3 to "The final answer is five centimetres"
            )
        )
        assertEquals(3, plan.segments.size)
        assertEquals(1, c.segmentBlockForProgress(plan.segments, 0.05f))
        assertEquals(2, c.segmentBlockForProgress(plan.segments, plan.segments[1].startFrac + 0.02f))
        assertEquals(3, c.segmentBlockForProgress(plan.segments, 0.99f))
        assertTrue(plan.script.contains("triangle.\nApply Pythagoras"))

        val pb = StudyPlayback()
        val started = ArrayList<Pair<Int, Boolean>>()
        var allDone = 0
        pb.listener = object : StudyPlayback.Listener {
            override fun onBlockStarted(index: Int, replay: Boolean) { started.add(index to replay) }
            override fun onAllFinished() { allDone++ }
        }
        pb.setGenerationOpen(false)
        repeat(4) { pb.addBlock(800) }
        pb.jumpToEnd()
        pb.startContinuousFrom(1)
        pb.narrationBusy = true
        assertEquals(StudyPlayback.State.PLAYING, pb.state)
        assertEquals(1, pb.current)

        // Advance smoothly during continuous lecture without replay flag
        var t = 0
        while (t < 1500) { pb.advance(100); t += 100 }
        assertEquals(1, pb.current) // held by continuous narrationBusy until next cue
        pb.advanceContinuous(2)
        assertEquals(2, pb.current)
        pb.advanceContinuous(3)
        assertEquals(3, pb.current)
        while (t < 3000) { pb.advance(100); t += 100 }
        pb.narrationBusy = false
        pb.advance(50)
        assertEquals(StudyPlayback.State.FINISHED, pb.state)
        assertTrue(allDone >= 1)
        assertTrue(started.contains(2 to false))
        assertTrue(started.contains(3 to false))
    }

    @Test fun playbackSyncsBlockWriteProgressWithSpokenVoicePacing() {
        val pb = StudyPlayback()
        pb.setGenerationOpen(false)
        pb.addBlock(2_000L)
        pb.addBlock(6_000L)
        pb.setBlockDuration(1, 9_000L)
        pb.jumpToEnd()
        pb.startContinuousFrom(1)
        pb.narrationBusy = true

        // Voice is only 25% into block 1: pen advances smoothly but does not finish far ahead
        pb.syncContinuousProgress(1, 0.25f)
        repeat(40) { pb.advance(100) }
        assertTrue(pb.progress <= 0.35f)
        assertEquals(1, pb.current)

        // Voice jumps to 80% of block 1: pen catches up cleanly right alongside the voice
        pb.syncContinuousProgress(1, 0.80f)
        assertTrue(pb.progress >= 0.72f)

        // Voice reaches end of block 1: pen completes smoothly
        pb.syncContinuousProgress(1, 1.0f)
        repeat(30) { pb.advance(100) }
        assertEquals(1f, pb.progress, 0.01f)
    }

    @Test fun segmentFrameProgressLocksToRecordedAudioBoundariesAndInterpolatesGaps() {
        val c = com.jarvis.assistant.study.voice.StudyVoiceCodec
        val plan = c.buildContinuousPlan(
            listOf(
                1 to "First we list the given force and mass values",
                2 to "Next apply Newton second law F equals m a",
                3 to "Finally divide force by mass to get acceleration"
            )
        )
        assertEquals(3, plan.segments.size)
        val totalFrames = 240_000L // 10 seconds at 24kHz
        // Segment 1 starts at 2.0s (48k frames), segment 2 wasn't matched (-1L -> interpolated smoothly)
        val bounds = longArrayOf(0L, 48_000L, -1L)

        val pStart = c.progressFromSegmentFrames(plan.segments, bounds, 0L, totalFrames)
        val pMid0 = c.progressFromSegmentFrames(plan.segments, bounds, 24_000L, totalFrames)
        val pAtSeg1 = c.progressFromSegmentFrames(plan.segments, bounds, 48_000L, totalFrames)
        val pLate = c.progressFromSegmentFrames(plan.segments, bounds, 180_000L, totalFrames)
        val pEnd = c.progressFromSegmentFrames(plan.segments, bounds, totalFrames, totalFrames)

        assertEquals(0f, pStart, 0.001f)
        assertTrue(pMid0 > pStart && pMid0 < plan.segments[0].endFrac)
        assertEquals(plan.segments[1].startFrac, pAtSeg1, 0.01f)
        assertTrue(pLate > pAtSeg1 && pLate > plan.segments[2].startFrac)
        assertTrue(pEnd in 0.98f..0.995f)
    }

    @Test fun keepPrintedReplayPreservesExistingBoardWhileAdvancingSpotlight() {
        val pb = StudyPlayback()
        pb.setGenerationOpen(false)
        repeat(4) { pb.addBlock(1_500L) }
        pb.jumpToEnd()
        pb.startContinuousFrom(1, keepPrinted = true)
        assertTrue(pb.continuousKeepPrinted)
        assertTrue(pb.othersStayVisible)
        assertEquals(3, pb.highWater)

        pb.advanceContinuous(2)
        assertTrue(pb.continuousKeepPrinted)
        assertTrue(pb.othersStayVisible)
        assertEquals(3, pb.highWater)

        pb.replayAll()
        assertFalse(pb.continuousKeepPrinted)
        assertFalse(pb.othersStayVisible)
    }

    @Test fun teacherPromptIncludesCheckInsMemoryTricksAndTransitions() {
        val prompt = StudyPrompt.lessonSystem(StudyProfile(language = "English"))
        assertTrue(prompt.contains("Never write greetings"))
        assertTrue(prompt.contains("Key Point & Memory Trick"))
        assertTrue(prompt.contains("I hope you understood"))
        assertTrue(prompt.contains("connects to the previous step"))
    }
}
