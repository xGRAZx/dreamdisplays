package com.dreamdisplays.platform.client.diagnostics

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** Puts a client-side line in the chat, whatever the Minecraft version calls it. Main thread. */
internal object ClientChat {
    fun show(message: Component) {
        val mc = Minecraft.getInstance()
        //? if >=26.2 {
        mc.gui.chatListener().handleSystemMessage(message, false)
        //?}
        //? if >=26.1 <26.2 {
        mc.gui.chat.addClientSystemMessage(message)
        //?}
        //? if <26.1 {
        mc.gui.chat.addMessage(message)
        //?}
    }
}
