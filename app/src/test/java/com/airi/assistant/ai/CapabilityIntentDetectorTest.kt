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
    fun mentioningTodayInsideCreativeRequestDoesNotRequireCurrentTime() {
        assertFalse(CapabilityIntentDetector.detect("اكتب لي قصة عن today في المدينة")
            .requires(CapabilityIntentDetector.Capability.CURRENT_TIME))
        assertFalse(CapabilityIntentDetector.detect("اكتب لي قصة عن اليوم في المدينة")
            .requires(CapabilityIntentDetector.Capability.CURRENT_TIME))
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

    // Report 1, stage 13: phrase lists are normalized like the input.
    @Test
    fun hamzaSpellingsInPhraseListsStillMatch() {
        val web = CapabilityIntentDetector.Capability.WEB_SEARCH
        assertTrue(CapabilityIntentDetector.detect("ابحث على الإنترنت عن سعر الذهب").requires(web))
        assertTrue(CapabilityIntentDetector.detect("ابحث على الانترنت عن سعر الذهب").requires(web))
        assertTrue(CapabilityIntentDetector.detect("أعطني آخر الأخبار").requires(web))
        assertTrue(CapabilityIntentDetector.detect("اعطني اخر الاخبار").requires(web))
    }

    @Test
    fun deviceActionMatchesWithAndWithoutHamza() {
        val action = CapabilityIntentDetector.Capability.DEVICE_ACTION
        assertTrue(CapabilityIntentDetector.detect("أطفئ الفلاش").requires(action))
        assertTrue(CapabilityIntentDetector.detect("اطفئ الفلاش").requires(action))
        assertTrue(CapabilityIntentDetector.detect("قم بإيقاف البلوتوث").requires(action))
        assertTrue(CapabilityIntentDetector.detect("قم بايقاف البلوتوث").requires(action))
    }

    @Test
    fun capabilityDiscoveryMatchesWithoutHamza() {
        assertTrue(CapabilityIntentDetector.isCapabilityDiscovery("ما هي الأدوات المتاحة"))
        assertTrue(CapabilityIntentDetector.isCapabilityDiscovery("ما هي الادوات المتاحة"))
    }

    // Report 1, stage 14: short terms must stand alone.
    @Test
    fun shortTermsDoNotMatchInsideOtherWords() {
        val terminal = CapabilityIntentDetector.Capability.TERMINAL_EXECUTE
        assertFalse(CapabilityIntentDetector.detect("explain the shell and locate the file").requires(terminal))
        assertTrue(CapabilityIntentDetector.detect("run ls in the terminal").requires(terminal))
        assertFalse(CapabilityIntentDetector.detect("هذا الموقع يفعل ذلك")
            .requires(CapabilityIntentDetector.Capability.DEVICE_ACTION))
    }

    // Report 1, item 8 (stage 24): a command can be requested without naming the terminal.
    @Test
    fun commandRequestsReachTheTerminalWithoutNamingIt() {
        val terminal = CapabilityIntentDetector.Capability.TERMINAL_EXECUTE
        listOf(
            "run `ls -la`",
            "execute the command git status",
            "run ls",
            "نفذ الأمر pwd",
            "شغل الأمر التالي: git log",
        ).forEach { input ->
            assertTrue(input, CapabilityIntentDetector.detect(input).requires(terminal))
        }
    }

    @Test
    fun everydayUseOfRunAndExecuteDoesNotReachTheTerminal() {
        val terminal = CapabilityIntentDetector.Capability.TERMINAL_EXECUTE
        listOf(
            "how do I run faster",
            "execute the plan carefully",
            "نفذ المهمة غدا",
            "شغل الاغنية",
            "ماذا تعرف عن ls في لينكس",
            "show me `ls` output format",
        ).forEach { input ->
            assertFalse(input, CapabilityIntentDetector.detect(input).requires(terminal))
        }
    }

    @Test
    fun impliedPersonalDataRequestsReachConnectorsForReadOnly() {
        val read = CapabilityIntentDetector.Capability.CONNECTOR_READ
        val write = CapabilityIntentDetector.Capability.CONNECTOR_WRITE
        val mail = CapabilityIntentDetector.detect("what is new in my inbox")
        assertTrue(mail.requires(read))
        assertFalse(mail.requires(write))
        assertTrue(mail.connectorIds.contains("google"))
        val repos = CapabilityIntentDetector.detect("لخص مستودعاتي")
        assertTrue(repos.connectorIds.contains("github"))
        assertFalse(repos.requires(write))
    }

    @Test
    fun impliedTargetsNeverAddWriteAccess() {
        val write = CapabilityIntentDetector.Capability.CONNECTOR_WRITE
        assertFalse(CapabilityIntentDetector.detect("ارسل بريدي للجميع").requires(write))
    }

    @Test
    fun fileCreationRequestsOfferTheWriteFileTool() {
        val fileWrite = CapabilityIntentDetector.Capability.FILE_WRITE
        listOf("اكتب ملف يحتوي الكود", "save it as a file", "انشئ ملف README", "احفظه في ملف")
            .forEach { assertTrue(it, CapabilityIntentDetector.detect(it).requires(fileWrite)) }
        listOf("ما هو الملف الشخصي", "how do I open a file", "explain files in linux")
            .forEach { assertFalse(it, CapabilityIntentDetector.detect(it).requires(fileWrite)) }
    }
}
