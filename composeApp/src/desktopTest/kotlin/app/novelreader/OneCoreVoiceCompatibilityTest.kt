package app.novelreader

import app.novelreader.tts.resolveOneCoreVoiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OneCoreVoiceCompatibilityTest {
    @Test
    fun `saved SAPI names resolve to OneCore or the default voice`() {
        val voices = listOf("Microsoft Hanhan", "Microsoft David", "Microsoft Yating")

        assertEquals("Microsoft Hanhan", resolveOneCoreVoiceId("Microsoft Hanhan Desktop", voices))
        assertEquals("Microsoft David", resolveOneCoreVoiceId("Microsoft David", voices))
        assertEquals("Microsoft Yating", resolveOneCoreVoiceId("Microsoft Yating", voices))
        assertEquals("Microsoft David", resolveOneCoreVoiceId("microsoft david", voices))
        assertNull(resolveOneCoreVoiceId("removed SAPI voice", voices))
        assertNull(resolveOneCoreVoiceId(null, voices))
        assertNull(resolveOneCoreVoiceId("", voices))
        assertNull(resolveOneCoreVoiceId("Microsoft David Desktop", emptyList()))
    }
}
