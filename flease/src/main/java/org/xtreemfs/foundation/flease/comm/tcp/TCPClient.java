/*
 * Copyright (C) 2013  Ohm Data
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *  This file incorporates work covered by the following copyright and
 *  permission notice:
 */

/*
 * Copyright (c) 2009-2010 by Bjoern Kolbeck, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.foundation.flease.comm.tcp;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xtreemfs.foundation.TimeSync;
import org.xtreemfs.foundation.buffer.ReusableBuffer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 *
 * @author bjko
 */
public class TCPClient {
    private final static Logger LOG = LoggerFactory.getLogger(TCPClient.class);

    private static final long MAX_WAITTIME_MS = 1000 * 60 * 10;

    final Map<InetSocketAddress, ClientConnection> connections;

    final ReadWriteLock conLock;

    final TCPCommunicator server;

    final NIOServer implementation;

    final Timer     closeTimer;

    public TCPClient(int port, InetAddress bindAddr, final NIOServer implementation) throws IOException {
        conLock = new ReentrantReadWriteLock();
        connections = new HashMap();
        this.implementation = implementation;
        NIOServer impl = new NIOServer() {

            public void onAccept(NIOConnection connection) {
                final InetSocketAddress endpt = connection.getEndpoint();
                try {
                    conLock.writeLock().lock();
                    ClientConnection cc = connections.get(endpt);
                    if (cc == null) {
                        cc = new ClientConnection();
                        cc.setConnection(connection);
                        cc.connectSucces();
                        connections.put(endpt,cc);
                    }
                } finally {
                    conLock.writeLock().unlock();
                }
                implementation.onAccept(connection);
            }

            public void onConnect(NIOConnection connection) {
                final InetSocketAddress endpt = connection.getEndpoint();
                try {
                    conLock.writeLock().lock();
                    ClientConnection cc = connections.get(endpt);
                    if (cc != null)
                        cc.connectSucces();
                    else {
                        LOG.error("connect for unknown connection: {}", connection);
                        connection.close();
                    }
                } finally {
                    conLock.writeLock().unlock();
                }
                implementation.onConnect(connection);
            }

            public void onRead(NIOConnection connection, ReusableBuffer buffer) {
                implementation.onRead(connection, buffer);
            }

            public void onClose(NIOConnection connection) {
                final InetSocketAddress endpt = connection.getEndpoint();
                try {
                    conLock.writeLock().lock();
                    connections.remove(endpt);
                } finally {
                    conLock.writeLock().unlock();
                }
                implementation.onClose(connection);
            }

            public void onWriteFailed(IOException exception, Object context) {
                implementation.onWriteFailed(exception, context);
            }

            public void onConnectFailed(InetSocketAddress endpoint, IOException exception, Object context) {
                System.out.println("connect failed for: "+endpoint);
                try {
                    conLock.readLock().lock();
                    ClientConnection cc = connections.get(endpoint);
                    if (cc != null) {
                        synchronized (cc) {
                            cc.connectFailed();
                            cc.setConnection(null);
                        }
                    }
                } finally {
                    conLock.readLock().unlock();
                }
                implementation.onConnectFailed(endpoint,exception,context);
            }
        };

        server = new TCPCommunicator(impl, port, bindAddr);

        closeTimer = new Timer();
        /*closeTimer.scheduleAtFixedRate(new TimerTask() {

            @Override
            public void run() {
                final long now = TimeSync.getLocalSystemTime();
                try {
                    conLock.writeLock().lock();
                    for (Entry<InetSocketAddress,ClientConnection> e : connections.entrySet()) {
                        if (e.getValue().lastConnectAttempt_ms)

                    }
                } finally {
                    conLock.writeLock().unlock();
                }
            }
        }, CON_TIMEOUT, CON_TIMEOUT);
        */

    }

    public void write(InetSocketAddress remote, ReusableBuffer data, Object context) {
        ClientConnection client = null;
        try {
            conLock.readLock().lock();
            client = connections.get(remote);
        } finally {
            conLock.readLock().unlock();
        }
        if (client == null) {
            try {
                conLock.writeLock().lock();
                client = connections.get(remote);
                if (client == null) {
                    client = new ClientConnection();
                    connections.put(remote, client);
                }
            } finally {
                conLock.writeLock().unlock();
            }
        }
        synchronized (client) {
            try {
                if (!client.isConnected()) {
                    if (client.canReconnect()) {
                        NIOConnection con = server.connect(remote,null);
                        client.setConnection(con);
                    } else {
                        implementation.onWriteFailed(new IOException("cannot connect to server, blocked due to reconnect timeout"), context);
                        return;
                    }
                }
            } catch (IOException ex) {
                implementation.onWriteFailed(ex, context);
                return;
            }
            assert(client.getConnection() != null);
            client.getConnection().write(data, context);
        }


    }

    public void start() {
        server.start();
    }

    public void startAndWait() {
        server.startAndWait();
    }

    public void stopAndWait() {
        server.stopAndWait();
        closeTimer.cancel();
    }

    public int getSendQueueSize() {
        return server.getSendQueueSize();
    }

    public void setLifeCycleListener(Service.Listener l) {
        server.addListener(l, MoreExecutors.sameThreadExecutor());
    }

    private static class ClientConnection {

        NIOConnection connection;

        long lastConnectAttempt_ms;

        long waitTime_ms;

        public ClientConnection() {
            lastConnectAttempt_ms = 0;
            waitTime_ms = 1000;
        }

        public NIOConnection getConnection() {
            return connection;
        }

        public void setConnection(NIOConnection connection) {
            this.connection = connection;
        }

        public boolean canReconnect() {
            return (lastConnectAttempt_ms + waitTime_ms < TimeSync.getLocalSystemTime());
        }

        public boolean isConnected() {
            return (connection != null);
        }

        public void connectSucces() {
            waitTime_ms = 1000;
            lastConnectAttempt_ms = 0;
        }

        public void connectFailed() {
            lastConnectAttempt_ms = TimeSync.getLocalSystemTime();
            waitTime_ms = waitTime_ms * 2;
            if (waitTime_ms > MAX_WAITTIME_MS) {
                waitTime_ms = MAX_WAITTIME_MS;
            }
        }
    }
}
