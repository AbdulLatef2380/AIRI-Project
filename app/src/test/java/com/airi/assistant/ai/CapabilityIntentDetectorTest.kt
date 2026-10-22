package com.airi.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityIntentDetectorTest {
    @Test
    fun naturalArabicRecentRequestsRequireWebSearch() {
        listOf(
            "ما آخر أخبار السودان؟",
            "أعطني أخبار السودان خلال آخر 24 ساعة",
            "كم سعر الدولار مقابل الجنيه السوداني اليوم؟",
            "ابحث عن أحدث إصدار لهذه المكتبة",
        ).forEach { input ->
            assertTrue(input, CapabilityIntentDetector.detect(input)
                .requires(CapabilityIntentDetector.Capability.WEB_SEARCH))
        }
    }

    @Test
    fun creativeTodayRequestDoesNotAutomaticallyRequireWebSearch() {
        assertFalse(CapabilityIntentDetector.detect("اكتب لي قصة عن يوم جميل")
            .requires(CapabilityIntentDetector.Capability.WEB_SEARCH))
    }

    @Test
    fun arabicDateVariantsRequireCurrentTime() {
        assertTrue(CapabilityIntentDetector.detect("كم اليوم بالتاريخ الميلادي؟")
            .requires(CapabilityIntentDetector.Capability.CURRENT_TIME))
        assertTrue(CapabilityIntentDetector.detect("ما تاريخ اليوم ميلادي؟")
            .requires(CapabilityIntentDetector.Capability.CURRENT_TIME))
    }

    @Test
    fun memoryQuestionRequiresMemoryRead() {
        val intent = CapabilityIntentDetector.detect("ماذا تتذكر عني؟")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.MEMORY_READ))
        assertTrue(intent.requiresTools)
    }

    @Test
    fun gmailSummaryRequiresGoogleConnectorRead() {
        val intent = CapabilityIntentDetector.detect("لخص لي آخر رسائل Gmail")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(intent.connectorIds.contains("google"))
    }

    @Test
    fun ownershipQuestionTargetsGitHubConnector() {
        val intent = CapabilityIntentDetector.detect("اعرض مستودعاتي في GitHub")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(intent.connectorIds.contains("github"))
    }

    @Test
    fun githubRepositoryUrlTargetsConnectorEvenWithoutOwnershipWording() {
        val intent = CapabilityIntentDetector.detect(
            "حلل هذا المشروع https://github.com/AbdulLatef2380/AIRI-Project.git"
        )
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(intent.connectorIds.contains("github"))
    }

    @Test
    fun arabicGithubRepositoryAnalysisTargetsConnector() {
        val intent = CapabilityIntentDetector.detect("اقرأ المستودع في GitHub وحلل الكود")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(intent.connectorIds.contains("github"))
    }

    @Test
    fun genericArabicCapabilityQuestionRequestsAllConnectors() {
        val intent = CapabilityIntentDetector.detect("ما هي الأدوات والمهارات والموصلات المتاحة؟")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertFalse(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_WRITE))
        assertTrue(intent.connectorIds.contains("*"))
        assertTrue(intent.requiresTools)
        assertEquals(QueryType.ANALYTICAL, QueryClassifier.classifyQuery("ما هي الأدوات والمهارات والموصلات المتاحة؟"))
    }

    @Test
    fun ArabicAllConnectorsTargetIsNotDroppedByWildcardHandling() {
        val intent = CapabilityIntentDetector.detect("اعرض الموصلات المتصلة")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(intent.connectorIds.contains("*"))
    }

    @Test
    fun ArabicCountAndEnabledQuestionRequestsFullCapabilityInventory() {
        val intent = CapabilityIntentDetector.detect(
            "اكتب لي عن نفسك كم موصل لديك وما الذي تم تفعيله الآن وكم أداة لديك وكم منها لديك وصول لها وكم مهارة لديك وكم منها تعمل"
        )
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(intent.connectorIds.contains("*"))
        assertTrue(CapabilityIntentDetector.isCapabilityDiscovery("How many connectors and skills are enabled?"))
    }

    @Test
    fun googleDriveOwnershipQuestionTargetsGoogleConnector() {
        val intent = CapabilityIntentDetector.detect("اعرض ملفاتي في Google Drive")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(intent.connectorIds.contains("google"))
    }

    @Test
    fun compoundRequestKeepsEveryMentionedConnector() {
        val intent = CapabilityIntentDetector.detect("اعرض رسائل Gmail ومستودعاتي في GitHub")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(intent.connectorIds.contains("google"))
        assertTrue(intent.connectorIds.contains("github"))
        assertEquals(setOf("google", "github"), intent.connectorIds)
    }

    @Test
    fun connectorMutationRequestsExposeWriteCapabilityNotReadOnly() {
        val github = CapabilityIntentDetector.detect("أنشئ issue في GitHub")
        assertTrue(github.requires(CapabilityIntentDetector.Capability.CONNECTOR_WRITE))
        assertFalse(github.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(github.connectorIds.contains("github"))

        val gmail = CapabilityIntentDetector.detect("أرسل رسالة عبر Gmail")
        assertTrue(gmail.requires(CapabilityIntentDetector.Capability.CONNECTOR_WRITE))
        assertFalse(gmail.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(gmail.connectorIds.contains("google"))
    }

    @Test
    fun englishUpdateAndDeleteRequestsAreWriteIntents() {
        listOf(
            "update my GitHub issue",
            "delete the Telegram message",
            "create a Gmail draft",
            "upload this file to Google Drive",
        ).forEach { input ->
            val intent = CapabilityIntentDetector.detect(input)
            assertTrue(input, intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_WRITE))
            assertTrue(input, intent.connectorIds.isNotEmpty())
        }
    }

    @Test
    fun arabicUpdateVariantsAreWriteIntents() {
        listOf(
            "حدّث issue في GitHub",
            "حدث الرسالة في Gmail",
            "عدّل الملف في Google Drive",
            "احذف الرسالة من Telegram",
        ).forEach { input ->
            val intent = CapabilityIntentDetector.detect(input)
            assertTrue(input, intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_WRITE))
            assertTrue(input, intent.connectorIds.isNotEmpty())
        }
    }

    @Test
    fun creativeRequestWithoutLiveDependencyNeedsNoTools() {
        assertTrue(CapabilityIntentDetector.detect("اكتب لي قصة قصيرة").capabilities.isEmpty())
    }

    @Test
    fun arabicDeviceCommandsRequireDeviceAction() {
        assertTrue(CapabilityIntentDetector.detect("قم بتشغيل فلاش الجهاز")
            .requires(CapabilityIntentDetector.Capability.DEVICE_ACTION))
        assertTrue(CapabilityIntentDetector.detect("شغّل الواي فاي")
            .requires(CapabilityIntentDetector.Capability.DEVICE_ACTION))
        assertTrue(CapabilityIntentDetector.detect("أطفئ البلوتوث")
            .requires(CapabilityIntentDetector.Capability.DEVICE_ACTION))
    }

    @Test
    fun deviceCommandsAreActionsEvenWhenShort() {
        assertEquals(QueryType.ACTION, QueryClassifier.classifyQuery("شغّل الواي فاي"))
    }

    @Test
    fun terminalCommandRequestsToolsAndActionProfile() {
        val intent = CapabilityIntentDetector.detect("اعرض ملفات sandbox")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.TERMINAL_EXECUTE))
        assertTrue(intent.requiresTools)
        assertEquals(QueryType.ACTION, QueryClassifier.classifyQuery("اعرض ملفات sandbox"))
    }

    @Test
    fun terminalMentionWithoutCommandDoesNotAuthorizeExecution() {
        val intent = CapabilityIntentDetector.detect("ماذا عن terminal؟")
        assertTrue(intent.capabilities.isEmpty())
        assertEquals(QueryType.SIMPLE, QueryClassifier.classifyQuery("ماذا عن terminal؟"))
    }
}
