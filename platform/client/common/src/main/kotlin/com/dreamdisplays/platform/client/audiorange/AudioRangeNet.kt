package com.dreamdisplays.platform.client.audiorange

import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.platform.client.net.V2Payload
import com.dreamdisplays.shared.audiorange.AudioRangeProtocol
import com.dreamdisplays.shared.audiorange.RangeMessage
import com.dreamdisplays.shared.audiorange.ViewEdit
import com.dreamdisplays.shared.audiorange.ViewMessage
import com.dreamdisplays.shared.audiorange.ZoneMessage
import org.slf4j.LoggerFactory

/** Sends audiorange messages to the server on the normal v2 channel. */
internal object AudioRangeNet {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun hello() = send(AudioRangeProtocol.TYPE_HELLO, AudioRangeProtocol.HELLO)
    fun range(message: RangeMessage) = send(AudioRangeProtocol.TYPE_RANGE, message.encode())
    fun zone(message: ZoneMessage) = send(AudioRangeProtocol.TYPE_ZONE, message.encode())
    fun view(edit: ViewEdit) = send(AudioRangeProtocol.TYPE_VIEW, ViewMessage.encode(edit))

    private fun send(type: Int, text: String): Boolean = runCatching {
        ClientPacketManager.send(V2Payload(AudioRangeProtocol.frame(type, text)))
    }.onFailure { logger.debug("Could not send audiorange message {}", type, it) }.isSuccess
}
