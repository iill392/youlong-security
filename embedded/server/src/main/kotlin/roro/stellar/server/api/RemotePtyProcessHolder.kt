package roro.stellar.server.api

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.stellar.server.IRemotePtyProcess
import rikka.rish.RishHost
import roro.stellar.server.service.process.policy.ShellSession

class RemotePtyProcessHolder(
    private val host: RishHost,
    token: IBinder?,
    // 2026-10-24 新增：PTY 会话审计（交互内容无法逐条拦截，只能记录会话元数据）。
    private val session: ShellSession? = null
) : IRemotePtyProcess.Stub() {

    private val ptmxFd = ParcelFileDescriptor.fromFd(host.ptmx)

    init {
        token?.linkToDeath({ destroy() }, 0)
    }

    override fun getPtyFd(): ParcelFileDescriptor = ptmxFd

    override fun resize(size: Long) = host.setWindowSize(size)

    override fun waitFor(): Int {
        while (host.exitCode == Int.MAX_VALUE) Thread.sleep(50)
        session?.finish(host.exitCode)
        return host.exitCode
    }

    override fun destroy() {
        ptmxFd.close()
        session?.finish(-1)
    }
}
