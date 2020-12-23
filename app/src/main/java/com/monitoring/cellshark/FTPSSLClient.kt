package com.monitoring.cellshark

import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.lang.reflect.Constructor
import java.net.Socket
import java.util.*
import javax.net.ssl.SSLSocket
import kotlin.jvm.Throws

class FTPSSLClient : FTPSClient() {
    @Throws(IOException::class)
    override fun _prepareDataSocket_(socket: Socket) {
        if (socket is SSLSocket) {
            val sessionAux = (_socket_ as SSLSocket).session
            if (sessionAux.isValid) {
                val sessionsContext = sessionAux.sessionContext
                try {
                    // lets find the sessions in the context' cache
                    val fieldSessionsInContext =
                        sessionsContext.javaClass.getDeclaredField("sessionsByHostAndPort")
                    fieldSessionsInContext.isAccessible = true
                    val sessionsInContext = fieldSessionsInContext[sessionsContext]

                    // lets find the session of our conexion
                    val portNumb = sessionAux.peerPort
                    val keys: Set<*> = (sessionsInContext as HashMap<*, *>).keys
                    if (keys.isEmpty()) throw IOException("Invalid SSL Session")
                    val fieldPort = keys.toTypedArray()[0]!!.javaClass.getDeclaredField("port")
                    fieldPort.isAccessible = true
                    var i = 0
                    while (i < keys.size && fieldPort[keys.toTypedArray()[i]] as Int != portNumb) i++
                    if (i < keys.size) // it was found
                    {
                        val ourKey = keys.toTypedArray()[i]!!
                        // building two objects like our key but with the new port and the host Name and host address
                        val construc: Constructor<Any> = ourKey.javaClass.getDeclaredConstructor(
                            String::class.java,
                            Int::class.javaPrimitiveType
                        )
                        construc.isAccessible = true
                        val copy1Key: Any =
                            construc.newInstance(socket.getInetAddress().hostName, socket.getPort())
                        val copy2Key: Any = construc.newInstance(
                            socket.getInetAddress().hostAddress,
                            socket.getPort()
                        )

                        // getting our session
                        val ourSession = sessionsInContext[ourKey]

                        // Lets add the pairs copy1Key-ourSession & copy2Key-ourSession to the context'cache
                        val method = sessionsInContext.javaClass.getDeclaredMethod(
                            "put",
                            Any::class.java,
                            Any::class.java
                        )
                        method.isAccessible = true
                        method.invoke(sessionsInContext, copy1Key, ourSession)
                        method.invoke(sessionsInContext, copy2Key, ourSession)
                    } else throw IOException("Invalid SSL Session")
                } catch (e: NoSuchFieldException) {
                    throw IOException(e)
                } catch (e: Exception) {
                    throw IOException(e)
                }
            } else {
                throw IOException("Invalid SSL Session")
            }
        }
    }
}