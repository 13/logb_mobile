package dev.logb.android.feature.update

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The in-app updater fetches the release list and the APK from GitHub. A user-installed CA (an
 * MDM profile, a proxy root) must not be able to rewrite what an update is, so GitHub's hosts
 * must trust only the system certificate store — unlike `base-config`, which also trusts the
 * user store for self-hosters running an internal CA on their own LogB server.
 */
class NetworkSecurityConfigTest {
    private val githubHosts =
        setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

    /** Gradle runs unit tests with the module directory (`app/`) as the working directory. */
    private fun configFile(): File {
        val file = File("src/main/res/xml/network_security_config.xml")
        assertTrue(
            "expected to find network_security_config.xml at ${file.absolutePath} " +
                "(is the test running with app/ as its working directory?)",
            file.isFile,
        )
        return file
    }

    private fun certSources(trustAnchors: Element): Set<String> {
        val certs = trustAnchors.getElementsByTagName("certificates")
        return (0 until certs.length)
            .map { (certs.item(it) as Element).getAttribute("src") }
            .toSet()
    }

    private fun singleElement(parent: Element, tag: String): Element {
        val nodes = parent.getElementsByTagName(tag)
        assertEquals("expected exactly one <$tag>", 1, nodes.length)
        return nodes.item(0) as Element
    }

    @Test fun `base-config still trusts both system and user certificates`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(configFile())
        val baseConfig = singleElement(doc.documentElement, "base-config")
        val trustAnchors = singleElement(baseConfig, "trust-anchors")
        assertEquals(setOf("system", "user"), certSources(trustAnchors))
    }

    @Test fun `domain-config lists exactly the four GitHub hosts without subdomains`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(configFile())
        val domainConfig = singleElement(doc.documentElement, "domain-config")
        val domainNodes = domainConfig.getElementsByTagName("domain")
        val domains = (0 until domainNodes.length).map { domainNodes.item(it) as Element }

        assertEquals(githubHosts, domains.map { it.textContent.trim() }.toSet())
        domains.forEach {
            assertEquals(
                "expected includeSubdomains=false for ${it.textContent.trim()}",
                "false",
                it.getAttribute("includeSubdomains"),
            )
        }
    }

    @Test fun `domain-config trusts the system store only, not the user store`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(configFile())
        val domainConfig = singleElement(doc.documentElement, "domain-config")
        val trustAnchors = singleElement(domainConfig, "trust-anchors")
        val sources = certSources(trustAnchors)

        assertTrue("expected system to be a trusted source", sources.contains("system"))
        assertFalse("a user-installed CA must not be trusted for GitHub", sources.contains("user"))
    }

    /**
     * `base-config` still permits cleartext (self-hosted LAN servers need it), but GitHub must
     * never be reached over plain http even though it inherits everything else from base-config.
     */
    @Test fun `domain-config forbids cleartext traffic to GitHub`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(configFile())
        val domainConfig = singleElement(doc.documentElement, "domain-config")
        assertEquals("false", domainConfig.getAttribute("cleartextTrafficPermitted"))
    }

    @Test fun `base-config still permits cleartext for self-hosted LAN servers`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(configFile())
        val baseConfig = singleElement(doc.documentElement, "base-config")
        assertEquals("true", baseConfig.getAttribute("cleartextTrafficPermitted"))
    }
}
