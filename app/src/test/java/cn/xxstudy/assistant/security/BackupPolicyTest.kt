package cn.xxstudy.assistant.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class BackupPolicyTest {
    private fun root(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory.newDocumentBuilder().parse(File(path)).documentElement
    }

    private fun assertExclusions(element: Element) {
        val exclusions = element.getElementsByTagName("exclude")
        val domains = (0 until exclusions.length).map {
            val entry = exclusions.item(it) as Element
            assertEquals(".", entry.getAttribute("path"))
            entry.getAttribute("domain")
        }.toSet()
        assertEquals(setOf("database", "sharedpref", "file", "root", "external"), domains)
    }

    @Test fun legacyBackupExcludesAllLocalData() {
        assertExclusions(root("src/main/res/xml/backup_rules.xml"))
    }
    @Test fun cloudAndDeviceTransferExcludeAllLocalData() {
        val root = root("src/main/res/xml/data_extraction_rules.xml")
        assertExclusions(root.getElementsByTagName("cloud-backup").item(0) as Element)
        assertExclusions(root.getElementsByTagName("device-transfer").item(0) as Element)
    }
    @Test fun manifestDoesNotOptIntoAutomaticBackup() {
        val application = root("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttribute("android:allowBackup"))
    }
}
