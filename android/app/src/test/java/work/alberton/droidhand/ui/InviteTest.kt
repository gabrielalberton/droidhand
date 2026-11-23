package work.alberton.droidhand.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteTest {
    private val tok = "0123456789abcdef".repeat(4)

    @Test fun readsAddressAndTokenFromAnInvite() {
        val text = "Convite do DroidHand para Tablet\n\n1. Baixe o app: https://github.com/x/releases/latest\n" +
            "2. Abra DroidHand → Conexão → Colar convite:\nRelay: ws://100.81.104.50:8787\nToken: $tok"
        assertEquals("ws://100.81.104.50:8787" to tok, Invite.parse(text))
    }

    @Test fun ignoresTextWithoutBoth() {
        assertNull(Invite.parse("Relay: ws://10.0.0.1:8787"))
        assertNull(Invite.parse("Token: $tok"))
        assertNull(Invite.parse("https://github.com/x/releases/latest"))
    }
}
