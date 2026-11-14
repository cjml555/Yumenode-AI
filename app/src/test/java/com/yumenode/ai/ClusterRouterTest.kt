// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests de la lógica pura del router de clúster: parseo de peers y selección.
 * Sin Robolectric en el proyecto, el estado se inyecta directo (campos internal).
 * `forward` y `runHealthCheck` tocan red → quedan para instrumentado.
 */
class ClusterRouterTest {

    @Before
    fun setUp() {
        ClusterRouter.enabled = false
        ClusterRouter.peers.clear()
    }

    @Test
    fun parsePeersSplitsPipeListAndDropsBlanks() {
        val peers = ClusterRouter.parsePeers("http://a:8080|http://b:8080|")
        assertEquals(listOf("http://a:8080", "http://b:8080"), peers.map { it.url })
        assertTrue(ClusterRouter.parsePeers("").isEmpty())
    }

    @Test
    fun enabledRequiresFlagPeersAndPremiumFlavor() {
        ClusterRouter.peers.add(ClusterRouter.Peer("http://a:8080"))
        ClusterRouter.enabled = true
        // free test task: gate must hold even with flag+peer; premium: all true
        assertEquals(FeatureToggleManager.isPremium, ClusterRouter.isEnabled())

        ClusterRouter.enabled = false
        assertFalse(ClusterRouter.isEnabled())

        ClusterRouter.peers.clear()
        ClusterRouter.enabled = true
        assertFalse(ClusterRouter.isEnabled())
    }

    @Test
    fun selectHealthyPeerRequiresPremiumAndHealthyPeer() {
        ClusterRouter.enabled = true
        ClusterRouter.peers.add(ClusterRouter.Peer("http://down:8080"))
        assertNull(ClusterRouter.selectHealthyPeer())
        assertFalse(ClusterRouter.isHealthy())

        ClusterRouter.peers.add(ClusterRouter.Peer("http://up:8080", healthy = true))
        val picked = ClusterRouter.selectHealthyPeer()
        if (FeatureToggleManager.isPremium) {
            assertEquals("http://up:8080", picked?.url)
            assertTrue(ClusterRouter.isHealthy())
        } else {
            // free flavor: never selected, even if healthy
            assertNull(picked)
            assertFalse(ClusterRouter.isHealthy())
        }
    }
}
