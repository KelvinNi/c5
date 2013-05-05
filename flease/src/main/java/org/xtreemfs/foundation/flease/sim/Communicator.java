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

package org.xtreemfs.foundation.flease.sim;

import com.google.common.util.concurrent.AbstractExecutionThreadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xtreemfs.foundation.flease.FleaseStage;
import org.xtreemfs.foundation.flease.comm.FleaseMessage;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A tool to simulate package loss, temporary host disconnects and message delay.
 * @author bjko
 */
public class Communicator extends AbstractExecutionThreadService {
    private static final Logger LOG = LoggerFactory.getLogger(Communicator.class);

    /**
     * the connected UDPSimSockets
     */
    protected final Map<Integer,FleaseStage> ports;

    /**
     * sockets which are currently blocked (i.e. simulated network outage)
     */
    protected final Map<Integer,Integer> blockedPorts;

    /**
     * the packet loss in percent (0..100)
     */
    private int pkgLossPct;

    /**
     * if true the thread will quit operation
     */
    private boolean quit;

    /**
     * queue with packets to be delivered to sockets
     */
    private final LinkedBlockingQueue<Packet> sendQ;

    /**
     * singleton
     */
    private static volatile Communicator theInstance;


    /**
     * thread for delayed delivery and blocking ports
     */
    private DelayedDelivery dd;
    private int minDelay;
    private int maxDelay;

    /**
     * percent of packets to be delayed
     */
    private int pctDelay;

    /**
     * if set to true, ports are blocked unsymmetric (i.e. receive but cannot send)
     */
    private boolean halfLink;

    @Override
    protected String serviceName() {
        return "Communicator";
    }

    /**
     * Creates a new instance of UDPSim
     * @param pkgLossPct packet loss in percent
     * @param minDelay minimum delay of a delayed packet
     * @param maxDelay maximum delay of a delayed packet
     * @param pctDelay percentage of packets to be delayed
     * @param halfLink if set to true, ports are blocked unsymmetric (i.e. receive but cannot send)
     * @param pHostUnavail probability (0..1) that a host becomes unavailable
     * @param pHostRecovery probability that a host is recovered. This value is multiplied by the
     * number of rounds that the host is already unavailable.
     */
    public Communicator(int pkgLossPct, int minDelay, int maxDelay, int pctDelay,
                  boolean halfLink, double pHostUnavail, double pHostRecovery) {

        this.ports = new ConcurrentHashMap();
        this.blockedPorts = new ConcurrentHashMap();
        this.pkgLossPct = pkgLossPct;
        this.quit = false;
        this.sendQ = new LinkedBlockingQueue();
        this.dd = new DelayedDelivery(sendQ,blockedPorts,ports,
                                        pHostUnavail,pHostRecovery);
        dd.start();
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        this.pctDelay = pctDelay;

        this.halfLink = halfLink;


        theInstance = this;
    }

    /**
     * opens a port and delivers messages into the queue
     * @param port port number to open
     * @return true if the port was opened succesfully, false if the port is already in use
     */
    public boolean openPort(int port, FleaseStage stage) {
        if (ports.get(port) != null)
            return false;

        ports.put(port,stage);
        return true;
    }

    /**
     * Opens a free port
     * @return treu if successful, false if there is no free port available
     */
    public int openPort(FleaseStage stage) {
        int tries = 0;
        while (tries < 5) {
            int rport = (int)(Math.random()*65000.0)+1;
            if (ports.get(rport) == null) {
                ports.put(rport,stage);
                return rport;
            }
            tries++;
        }
        return -1;
    }

    /**
     * closes the port
     * @param port port number to close
     */
    public void closePort(int port) {
        ports.remove(port);
    }

    /**
     * sends a datagram packet from
     * @param port sending port number
     * @param msg packet to send
     */
    public synchronized void send(int port, FleaseMessage msg) {
        Packet p = new Packet(msg,port);
        sendQ.add(p);
    }

    private Thread theThread;

    @Override
    protected void triggerShutdown() {
        theThread.interrupt();
    }

    /**
     * main loop
     */
    @Override
    public void run() throws Exception {
        theThread = Thread.currentThread();

        InetAddress ia = InetAddress.getLocalHost();

        while (isRunning()) {
            try {
                Packet p = sendQ.take();

                FleaseStage rec = ports.get(p.recipientPort);

                if (rec == null)
                    continue;

                if (blockedPorts.containsKey(p.msg.getSender().getPort())) {
                    LOG.debug("msg dropped, port blocked {}", p.msg.getSender().getPort());
                    continue;
                }

                if (blockedPorts.containsKey(p.recipientPort)) {
                    LOG.debug("msg dropped, port blocked {}", p.recipientPort);
                    continue;
                }

                if (!dropPacket() || p.requeued) {

                    int delay = delayPacket();
                    if ((delay > 0) && !p.requeued) {
                        LOG.debug("msg delayed {} ms {} -> {}", delay, p.recipientPort, p.msg.getSender().getPort());
                        dd.add(p,delay);
                    } else {

                        //p.msg.setSender(new InetSocketAddress(ia, p.recipientPort));
                        try {
                            rec.receiveMessage(p.msg);
                        } catch (IllegalStateException e) {
                            //just drop it
                        }
                    }
                } else {
                    LOG.debug("msg lost {} -> {}", p.recipientPort, p.msg.getSender().getPort());
                }
            } catch (InterruptedException ex) {
                LOG.error("run loop", ex);
            }
        }

    }

    /**
     * decides if a message should be dropped.
     * @return true, if the packet is to be dropped
     */
    private boolean dropPacket() {
        int rv = (int)(Math.random()*100.0);
        return (rv < this.pkgLossPct);
    }



    /**
     * singleton
     * @return null, if not initialized, the instance otherwise
     */
    public static Communicator getInstance() {
        return theInstance;
    }

    /**
     * decides if and how long a packet is delayed
     * @return delay in ms
     */
    private int delayPacket() {
        if (pctDelay == 0)
            return 0;
        int rv = (int)(Math.random()*100.0);
        if (rv < this.pctDelay) {
            return (int)(Math.random()*((double)maxDelay-minDelay))+minDelay;
        } else {
            return 0;
        }
    }

    /**
     * Packet information
     */
    protected static class Packet {
        /**
         * the datagram that is being sent
         */
        public FleaseMessage msg;
        /**
         * originating prot number
         */
        public int recipientPort;
        /**
         * set to true if it was requeued after delay (i.e. must not be dropped, delayed...)
         */
        public boolean requeued;
        /**
         * creates a new packet
         * @param msg flease message
         * @param port originating port
         */
        public Packet(FleaseMessage msg, int port) {
            this.msg = msg;
            this.recipientPort = port;
            this.requeued = false;
        }
    }

}
