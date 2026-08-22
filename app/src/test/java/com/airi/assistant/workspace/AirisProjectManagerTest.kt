package com.airi.assistant.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class AirisProjectManagerTest {

    @Before
    fun setUp() {
        AirisProjectManager.clear()
    }

    @Test
    fun managesProjectsAndAttachedFiles() {
        val proj = AirisProjectManager.createProject("proj-1", "Alpha Project", "Testing project workspace")
        AirisProjectManager.addFileToProject("proj-1", "docs/spec.md")

        val fetched = AirisProjectManager.getProject("proj-1")
        assertNotNull(fetched)
        assertEquals("Alpha Project", fetched?.title)
        assertEquals(1, fetched?.files?.size)
        assertEquals("docs/spec.md", fetched?.files?.get(0))
    }
}
