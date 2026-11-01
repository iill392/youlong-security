package roro.stellar.server.api

import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.stellar.server.IRemoteProcess
import roro.stellar.server.service.process.policy.ProcessTree
import roro.stellar.server.service.process.policy.ShellSession
import roro.stellar.server.service.process.policy.StdinGateway
import roro.stellar.server.util.Logger
import roro.stellar.server.util.ParcelFileDescriptorUtil
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

class RemoteProcessHolder(
    private val process: Process,
    token: IBinder?,
    // 2026-10-24 新增（《游龙 Shizuku 防御方案》）：stdin 代理网关 + 会话审计。
    // 为 null 时保持上游行为（直接透传 stdin）。
    private val session: ShellSession? = null
) : IRemoteProcess.Stub() {
    private var `in`: ParcelFileDescriptor? = null

    private var out: ParcelFileDescriptor? = null

    init {
        if (token != null) {
            try {
                val deathRecipient = DeathRecipient {
                    try {
                        if (alive()) {
                            destroy()
                            LOGGER.i("因为所有者已死亡，销毁进程")
                        }
                    } catch (e: Throwable) {
                        LOGGER.w(e, "销毁进程失败")
                    }
                }
                token.linkToDeath(deathRecipient, 0)
            } catch (e: Throwable) {
                LOGGER.w(e, "linkToDeath 失败")
            }
        }
    }

    override fun getOutputStream(): ParcelFileDescriptor? {
        if (out == null) {
            out = try {
                val s = session
                if (s != null) {
                    // 2026-10-24 新增：不再把真实进程 stdin 交给 App。
                    // 交给 App 的是我们自己的管道写端；读端交给网关线程做
                    // 「审计 + 判定 + 按需转发」。
                    val pipe = ParcelFileDescriptor.createPipe()
                    val gateway = StdinGateway(s, pipe[0], process.outputStream)
                    s.gateway = gateway
                    gateway.startGateway()
                    pipe[1]
                } else {
                    ParcelFileDescriptorUtil.pipeTo(process.outputStream)
                }
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }
        return out
    }

    override fun getInputStream(): ParcelFileDescriptor? {
        if (`in` == null) {
            `in` = try {
                ParcelFileDescriptorUtil.pipeFrom(process.inputStream)
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }
        return `in`
    }

    override fun getErrorStream(): ParcelFileDescriptor? = try {
        ParcelFileDescriptorUtil.pipeFrom(process.errorStream)
    } catch (e: IOException) {
        throw IllegalStateException(e)
    }

    // 2026-10-24 新增：会话结束时补一条审计；被策略阻断的会话对调用方统一返回 1。
    override fun waitFor(): Int {
        val code = try {
            process.waitFor()
        } catch (e: InterruptedException) {
            throw IllegalStateException(e)
        }
        val s = session
        if (s != null) {
            s.finish(code)
            if (s.blocked) return 1
        }
        return code
    }

    override fun exitValue(): Int = process.exitValue()

    override fun destroy() {
        // 2026-10-24 新增：销毁时连同本次会话派生的子进程一起回收（防 nohup 看门狗）。
        val s = session
        if (s != null) {
            ProcessTree.killSession(process)
            s.finish(-1)
        } else {
            process.destroy()
        }
    }

    override fun alive(): Boolean = try {
        exitValue()
        false
    } catch (e: IllegalThreadStateException) {
        true
    }

    @Throws(RemoteException::class)
    override fun waitForTimeout(timeout: Long, unitName: String?): Boolean {
        val unit = TimeUnit.valueOf(unitName!!)
        val startTime = System.nanoTime()
        var rem = unit.toNanos(timeout)

        do {
            try {
                val code = exitValue()
                session?.finish(code)
                return true
            } catch (ex: IllegalThreadStateException) {
                if (rem > 0) {
                    try {
                        Thread.sleep(
                            min(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100)
                        )
                    } catch (e: InterruptedException) {
                        throw IllegalStateException()
                    }
                }
            }
            rem = unit.toNanos(timeout) - (System.nanoTime() - startTime)
        } while (rem > 0)
        return false
    }

    companion object {
        private val LOGGER = Logger("RemoteProcessHolder")
    }
}