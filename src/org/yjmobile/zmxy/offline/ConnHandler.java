package org.yjmobile.zmxy.offline;

import java.net.Socket;

/**
 * Serves one accepted loopback connection on its own thread.
 *
 * <p>Why this exists: the client - and the Tencent TPNS SDK - keep idle sockets
 * open, and some of them send a single byte ("P") and then nothing. With a
 * single accept loop the next real request (login / roledata2 / savedata) sat in
 * the backlog until the 20 s read timeout of the stale socket expired; that is
 * the "转圈卡好久" stall seen on the pedestal/loading screens. Measured with
 * v411/probe_time.py: the first request took 15 s, the second 6 s, the rest 0 s.
 *
 * <p>Top-level class on purpose - d8 in this project has trouble with anonymous
 * inner classes (EnclosingMethod attribute).
 */
final class ConnHandler implements Runnable {

    private final LocalServer server;
    private final Socket socket;

    ConnHandler(LocalServer server, Socket socket) {
        this.server = server;
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            server.serve(socket);
        } catch (Throwable ignored) {
            // keep serving
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
