package com.recly.editor

import com.recly.core.media.ReclyMediaContract
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorContractTest {

    @Test
    fun testContractPackageNames() {
        assertEquals("com.recly.editor", ReclyMediaContract.EDITOR_PACKAGE_NAME)
        assertEquals("com.recly.recorder", ReclyMediaContract.RECORDER_PACKAGE_NAME)
        assertEquals("com.recly.action.EDIT_VIDEO", ReclyMediaContract.ACTION_EDIT_VIDEO)
    }
}
