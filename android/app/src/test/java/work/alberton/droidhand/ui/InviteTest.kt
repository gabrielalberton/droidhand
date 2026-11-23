package work.alberton.droidhand.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteTest {
    private val tok = "0123456789abcdef".repeat(4)

    @Test fun readsAddressAndTokenFromAnInvite() {
        val text = "Convite do DroidHand para Tablet\n\n1. Baixe o app: https://github.com/x/releases/latest\n" +
            "2. Abra DroidHand → Conexão → Colar convite:\nRelay: ws://100.81.104.50:8787\nToken: $tok"
        assertEquals(Invite.Data("ws://100.81.104.50:8787", tok), Invite.parse(text))
    }

    @Test fun ignoresTextWithoutBoth() {
        assertNull(Invite.parse("Relay: ws://10.0.0.1:8787"))
        assertNull(Invite.parse("Token: $tok"))
        assertNull(Invite.parse("https://github.com/x/releases/latest"))
    }

    @Test fun readsTheQrLinkBack() {
        val link = Invite.link("Tablet da Ana", "ws://100.81.104.50:8787", tok)
        assertEquals(true, link.startsWith(Invite.DOWNLOAD_URL + "#"))
        assertEquals(Invite.Data("ws://100.81.104.50:8787", tok, "Tablet da Ana"), Invite.parse(link))
    }

    @Test fun readsTheSetupPageAppLink() {
        val link = "droidhand://connect#relay=ws://100.81.104.50:8787&token=$tok&name=Tablet%20da%20Ana"
        assertEquals(Invite.Data("ws://100.81.104.50:8787", tok, "Tablet da Ana"), Invite.parse(link))
    }

    @Test fun recognisesSetupLinks() {
        assertEquals(true, Invite.isSetupUrl("http://100.81.104.50:8787/setup/AbCdEfGhIjKlMnOpQrStUv"))
        assertEquals(false, Invite.isSetupUrl("http://100.81.104.50:8787/setup/short"))
        assertEquals(false, Invite.isSetupUrl("https://github.com/x/releases/latest"))
    }
}
