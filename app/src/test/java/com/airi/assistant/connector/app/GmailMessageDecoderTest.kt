package com.airi.assistant.connector.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class GmailMessageDecoderTest {
    @Test
    fun decodesPlainTextBody() {
        val payload = JSONObject()
            .put("mimeType", "text/plain")
            .put("body", JSONObject().put("data", encode("Sent body")))

        assertEquals("Sent body", GmailMessageDecoder.decode(payload))
    }

    @Test
    fun prefersPlainTextFromMultipartPayload() {
        val payload = JSONObject()
            .put("mimeType", "multipart/alternative")
            .put("parts", JSONArray()
                .put(JSONObject().put("mimeType", "text/html")
                    .put("body", JSONObject().put("data", encode("<b>HTML</b>"))))
                .put(JSONObject().put("mimeType", "text/plain")
                    .put("body", JSONObject().put("data", encode("Plain")))))

        assertEquals("Plain", GmailMessageDecoder.decode(payload))
    }

    @Test
    fun returnsEmptyForMissingBody() {
        assertEquals("", GmailMessageDecoder.decode(JSONObject().put("mimeType", "text/plain")))
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
