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

package org.xtreemfs.foundation.flease;

import com.google.common.util.concurrent.Service;
import junit.framework.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xtreemfs.foundation.TimeSync;
import org.xtreemfs.foundation.buffer.ASCIIString;
import org.xtreemfs.foundation.flease.comm.FleaseMessage;
import org.xtreemfs.foundation.flease.proposer.FleaseException;
import org.xtreemfs.foundation.util.FSUtils;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author bjko
 */
public class MasterEpochTest extends TestCase {
    private static final Logger LOG = LoggerFactory.getLogger(MasterEpochTest.class);

    private final FleaseConfig cfg;
    private final File testDir;

    public MasterEpochTest(String testName) throws FleaseException {
        super(testName);

        //Logging.start(Logging.LEVEL_WARN, Category.all);
        TimeSync.initializeLocal(50);

        cfg = new FleaseConfig(10000, 500, 500, new InetSocketAddress(12345), "localhost:12345",5, true, 0);
        testDir = new File("/tmp/xtreemfs-test/");
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        FSUtils.delTree(testDir);
        testDir.mkdirs();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Test of createTimer method, of class FleaseStage.
     */
    public void testOpenAndGetLease() throws Exception {
        final ASCIIString CELL_ID = new ASCIIString("testcell");

        final AtomicReference<Flease> result = new AtomicReference();

        FleaseStage fs = new FleaseStage(cfg, "/tmp/xtreemfs-test/", new FleaseMessageSenderInterface() {

            @Override
            public void sendMessage(FleaseMessage message, InetSocketAddress recipient) {
                //ignore me
            }
        }, true, new FleaseViewChangeListenerInterface() {

            @Override
            public void viewIdChangeEvent(ASCIIString cellId, int viewId) {
            }
        },new FleaseStatusListener() {

            @Override
            public void statusChanged(ASCIIString cellId, Flease lease) {
                // System.out.println("state change: "+cellId+" owner="+lease.getLeaseHolder());
                synchronized (result) {
                    result.set(new Flease(cellId, lease.getLeaseHolder(), lease.getLeaseTimeout_ms(),lease.getMasterEpochNumber()));
                    result.notify();
                }
            }

            @Override
            public void leaseFailed(ASCIIString cellId, FleaseException error) {
                MasterEpochTest.fail(error.toString());
            }
        }, new MasterEpochHandlerInterface() {

            long masterEpochNum = 0;

            @Override
            public void sendMasterEpoch(FleaseMessage response, Continuation callback) {
                // System.out.println("sending: "+masterEpochNum);
                response.setMasterEpochNumber(masterEpochNum);
                callback.processingFinished();
            }

            @Override
            public void storeMasterEpoch(FleaseMessage request, Continuation callback) {
                masterEpochNum = request.getMasterEpochNumber();
                // System.out.println("storing: "+masterEpochNum);
                callback.processingFinished();
            }
        });

        FleaseMessage msg = new FleaseMessage(FleaseMessage.MsgType.EVENT_RESTART);
        msg.setCellId(CELL_ID);

        fs.startAndWait();

        fs.openCell(CELL_ID, new ArrayList(),true);

        synchronized(result) {
            if (result.get() == null)
                result.wait(1000);
            if (result.get() == null)
                fail("timeout!");
        }

        assertEquals(result.get().getLeaseHolder(),cfg.getIdentity());
        assertEquals(result.get().getMasterEpochNumber(),1);

        FleaseFuture f = fs.closeCell(CELL_ID, false);
        f.get();

        Thread.sleep(12000);

        result.set(null);

        fs.openCell(CELL_ID, new ArrayList(), true);

        synchronized(result) {
            if (result.get() == null)
                result.wait(1000);
            if (result.get() == null)
                fail("timeout!");
        }

        assertEquals(result.get().getLeaseHolder(),cfg.getIdentity());
        assertEquals(result.get().getMasterEpochNumber(),2);

        fs.stopAndWait();
    }


    /**
     * Test of createTimer method, of class FleaseStage.
     */
    public void testSimpleMasterEpochHandler() throws Exception {
        final ASCIIString CELL_ID = new ASCIIString("testcell");

        final AtomicReference<Flease> result = new AtomicReference();

        SimpleMasterEpochHandler meHandler = new SimpleMasterEpochHandler("/tmp/xtreemfs-test/");
        Service.State svcState = meHandler.startAndWait();
        if (svcState != Service.State.RUNNING) {
            LOG.error("Unable to start Master Epoch Handler", meHandler.failureCause());
        }

        FleaseStage fs = new FleaseStage(cfg, "/tmp/xtreemfs-test/", new FleaseMessageSenderInterface() {

            @Override
            public void sendMessage(FleaseMessage message, InetSocketAddress recipient) {
                //ignore me
            }
        }, true, new FleaseViewChangeListenerInterface() {

            @Override
            public void viewIdChangeEvent(ASCIIString cellId, int viewId) {
            }
        },new FleaseStatusListener() {

            @Override
            public void statusChanged(ASCIIString cellId, Flease lease) {
                // System.out.println("state change: "+cellId+" owner="+lease.getLeaseHolder());
                synchronized (result) {
                    result.set(new Flease(cellId, lease.getLeaseHolder(), lease.getLeaseTimeout_ms(),lease.getMasterEpochNumber()));
                    result.notify();
                }
            }

            @Override
            public void leaseFailed(ASCIIString cellId, FleaseException error) {
                MasterEpochTest.fail(error.toString());
            }
        }, meHandler);

        FleaseMessage msg = new FleaseMessage(FleaseMessage.MsgType.EVENT_RESTART);
        msg.setCellId(CELL_ID);

        fs.startAndWait();

        fs.openCell(CELL_ID, new ArrayList(),true);

        synchronized(result) {
            if (result.get() == null)
                result.wait(1000);
            if (result.get() == null)
                fail("timeout!");
        }

        assertEquals(result.get().getLeaseHolder(),cfg.getIdentity());
        assertEquals(1, result.get().getMasterEpochNumber());

        FleaseFuture f = fs.closeCell(CELL_ID, false);
        f.get();

        fs.stopAndWait();
        meHandler.stopAndWait();

        Thread.sleep(12000);

        //restart
        meHandler = new SimpleMasterEpochHandler("/tmp/xtreemfs-test/");
        if (meHandler.startAndWait() != Service.State.RUNNING) {
            LOG.error("Couldnt start meHandler", meHandler.failureCause());
        }

        fs = new FleaseStage(cfg, "/tmp/xtreemfs-test/", new FleaseMessageSenderInterface() {

            @Override
            public void sendMessage(FleaseMessage message, InetSocketAddress recipient) {
                //ignore me
            }
        }, true, new FleaseViewChangeListenerInterface() {

            @Override
            public void viewIdChangeEvent(ASCIIString cellId, int viewId) {
            }
        },new FleaseStatusListener() {

            @Override
            public void statusChanged(ASCIIString cellId, Flease lease) {
                // System.out.println("state change: "+cellId+" owner="+lease.getLeaseHolder());
                synchronized (result) {
                    result.set(new Flease(cellId, lease.getLeaseHolder(), lease.getLeaseTimeout_ms(),lease.getMasterEpochNumber()));
                    result.notify();
                }
            }

            @Override
            public void leaseFailed(ASCIIString cellId, FleaseException error) {
                MasterEpochTest.fail(error.toString());
            }
        }, meHandler);

        fs.startAndWait();

        result.set(null);

        fs.openCell(CELL_ID, new ArrayList(), true);

        synchronized(result) {
            if (result.get() == null)
                result.wait(1000);
            if (result.get() == null)
                fail("timeout!");
        }

        assertEquals(result.get().getLeaseHolder(),cfg.getIdentity());
        assertEquals(result.get().getMasterEpochNumber(),2);

        fs.stopAndWait();

    }


}
