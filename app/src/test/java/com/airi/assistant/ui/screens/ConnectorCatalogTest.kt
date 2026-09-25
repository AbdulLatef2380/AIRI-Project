package com.airi.assistant.ui.screens

import com.airi.assistant.connector.OfficialConnectorCatalog
import com.airi.assistant.connector.toConnectorMeta
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorCatalogTest {
    @Test
    fun everyOfficialCatalogConnector_hasArabicPresentationAndUsage() {
        val arabic = Locale("ar")
        OfficialConnectorCatalog.all.forEach { definition ->
            val presentation = definition.toConnectorMeta().presentation(arabic)
            assertFalse("missing Arabic name for ${definition.id}", presentation.name.isBlank())
            assertFalse("missing Arabic description for ${definition.id}", presentation.description.isBlank())
            assertFalse("missing Arabic usage for ${definition.id}", presentation.howToUse.isBlank())
            assertNotNull("missing icon fallback for ${definition.id}", presentation.iconUrl)
        }
    }

    @Test
    fun presentation_keepsStableTechnicalIdentityOutsideUiCopy() {
        val definition = OfficialConnectorCatalog.get("github")!!
        val meta = definition.toConnectorMeta()
        val presentation = meta.presentation(Locale("ar"))
        assertTrue(meta.id == "github")
        assertTrue(presentation.name == "GitHub")
        assertTrue(meta.capabilities.any { it.id == "repositories.read" })
    }
}
