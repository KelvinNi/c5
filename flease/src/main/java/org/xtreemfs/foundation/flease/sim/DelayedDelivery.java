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

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xtreemfs.foundation.LifeCycleThread;
import org.xtreemfs.foundation.flease.FleaseStage;

/**
 * Thread to deliver delayed packets and to set availability status of hosts.
 * @author bjko
 */
public class DelayedDelivery extends LifeCycleThread {
  private static final Logger LOG = LoggerFactory.getLogger(DelayedDelivery.class);

    /**
     * queued packets for delayed delivery
     */
    private final LinkedList<DelayPacket> packets;

    /**
     * sending queue
     */
    private final LinkedBlockingQueue<Communicator.Packet> targetQ;

    /**
     * minimum delay in ms
     */
    private int minDelay;

    /**
     * maximum delay in ms
     */
    private int maxDelay;

    /**
     * if set to true the thread shuts down
     */
    private boolean quit;

    /**
     * time to wait between two invocations in ms
     */
    private static final int WAIT_TIME = 50;

    /**
     * list of blocked ports (unavailable hosts)
     */
    private final Map<Integer,Integer> blockedPorts;

    /**
     * all ports
     */
    private final Map<Integer,FleaseStage> ports;

    /**
     * probability of unavailable host
     */
    private double pHostUnavail;

    /**
     * probability of host recovery
     */
    private double pHostRecovery;

    /**
     * number of rounds (i.e. WAIT_TIME) to wait between checks for host availability
     */
    private final static int HOSTWAIT_MULTIPLIER = 10;

    /**
     * Creates a new instance of UDPDelayedDelivery
     * @param targetQ queue to use for delivering delayed packets
     * @param blockedPorts list of blocked ports (unavail hosts)
     * @param ports list of all hosts
     * @param pHostUnavail probability of unavailable host
     * @param pHostRecovery probability of host recovery
     */
    public DelayedDelivery(LinkedBlockingQueue<Communicator.Packet> targetQ,
            Map<Integer,Integer> blockedPorts,
            Map<Integer,FleaseStage> ports,
            double pHostUnavail, double pHostRecovery) {

        super("UDP-Delivery");

        this.packets = new LinkedList();
        this.targetQ = targetQ;
        /*this.minDelay = minDelay;
        this.maxDelay = maxDelay;*/

        this.pHostUnavail = pHostUnavail;
        this.pHostRecovery = pHostRecovery;

        this.blockedPorts = blockedPorts;
        this.ports = ports;

        this.quit = false;
    }

    /**
     * adds a packet for delyed delivery
     * @param p packet to send
     * @param delay delay in ms
     */
    public void add(Communicator.Packet p, int delay) {
        synchronized (packets) {
            DelayPacket dp = new DelayPacket();
            dp.packet = p;
            dp.waited = 0;
            dp.delay = delay;
            packets.add(dp);
        }
    }

    /**
     * main loop
     */
    public void run() {

        notifyStarted();

        int hostwait = 0;
        while (!quit) {
            synchronized (packets) {
                Iterator<DelayPacket> iter = packets.iterator();
                while (iter.hasNext()) {
                    DelayPacket dp = iter.next();
                    try {
                        dp.waited += WAIT_TIME;
                        if (dp.waited >= dp.delay) {
                            dp.packet.requeued = true;
                            iter.remove();
                            targetQ.add(dp.packet);
                        }
                    } catch (IllegalStateException ex) {
                    }
                }
            }
            //check only every 10*WAIT_TIME
            hostwait++;
            if (hostwait == HOSTWAIT_MULTIPLIER) {

                //check if hosts should become available again...
                Iterator<Integer> iter = blockedPorts.keySet().iterator();
                while (iter.hasNext()) {
                    int portNo = iter.next();
                    int round = blockedPorts.get(portNo);
                    if (Math.random() < this.pHostRecovery*round) {

                        iter.remove();
                        LOG.debug("unblocked {}", portNo);
                    } else {
                        blockedPorts.put(portNo,round+1);
                    }
                }

                //make hosts unavailable
                if (blockedPorts.size() < ports.size()) {
                    if (Math.random() < this.pHostUnavail) {
                        //get a random host to make unavailable
                        Integer[] keys = ports.keySet().toArray(new Integer[0]);
                        int rand = (int)(Math.random()*(double)(keys.length));
                        blockedPorts.put(keys[rand],1);
                        LOG.debug("blocked   {}", keys[rand]);
                    }
                }

                hostwait = 0;

            }

            synchronized (this) {
                try {
                    this.wait(WAIT_TIME);
                } catch (InterruptedException ex) {
                }
            }

        }

        notifyStopped();
    }

    /**
     * shuts down the thread
     */
    public void shutdown() {
        this.quit = true;
        this.interrupt();
    }

    /**
     * information on packet to be delayed
     */
    protected static class DelayPacket {
        /**
         * packet to send
         */
        Communicator.Packet packet;
        int delay;
        /**
         * time already waited
         */
        int waited;
    }

}
