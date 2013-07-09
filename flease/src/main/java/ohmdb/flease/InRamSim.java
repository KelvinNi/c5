package ohmdb.flease;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import ohmdb.flease.rpc.IncomingRpcReply;
import ohmdb.flease.rpc.IncomingRpcRequest;
import ohmdb.flease.rpc.OutgoingRpcReply;
import ohmdb.flease.rpc.OutgoingRpcRequest;
import org.jetlang.channels.AsyncRequest;
import org.jetlang.channels.MemoryRequestChannel;
import org.jetlang.channels.Request;
import org.jetlang.channels.RequestChannel;
import org.jetlang.core.Callback;
import org.jetlang.fibers.Fiber;
import org.jetlang.fibers.PoolFiberFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;

public class InRamSim {
    public static class Info implements InformationInterface {

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public long getLeaseLength() {
            return 10 * 1000;
        }
    }
    private static final Logger LOG = LoggerFactory.getLogger(InRamSim.class);

    final int peerSize;
    final Map<UUID, FleaseLease> fleaseRunners = new HashMap<>();
    final RequestChannel<OutgoingRpcRequest,IncomingRpcReply> rpcChannel = new MemoryRequestChannel<>();
    final Fiber rpcFiber;
    private final PoolFiberFactory fiberPool;
    private final MetricRegistry metrics = new MetricRegistry();

    public InRamSim(final int peerSize) {
        this.peerSize = peerSize;
        this.fiberPool = new PoolFiberFactory(Executors.newCachedThreadPool());

        List<UUID> peerUUIDs = new ArrayList<>();
        for (int i = 0; i < peerSize; i++) {
            peerUUIDs.add(UUID.randomUUID());
        }

        for( UUID peerId : peerUUIDs) {
            // make me a ....
            FleaseLease fl = new FleaseLease(fiberPool.create(), new Info(), peerId.toString(), "lease", peerId, peerUUIDs, rpcChannel);
            fleaseRunners.put(peerId, fl);
        }

        rpcFiber = fiberPool.create();


        // subscribe to the rpcChannel:
        rpcChannel.subscribe(rpcFiber, new Callback<Request<OutgoingRpcRequest, IncomingRpcReply>>() {
            @Override
            public void onMessage(Request<OutgoingRpcRequest, IncomingRpcReply> message) {
                messageForwarder(message);
            }
        });

        rpcFiber.start();
    }


    private final Meter messages = metrics.meter(name(InRamSim.class, "messageRate"));
    private final Counter messageCnt = metrics.counter(name(InRamSim.class, "messageCnt"));

    private void messageForwarder(final Request<OutgoingRpcRequest, IncomingRpcReply> origMsg) {

        // ok, who sent this?!!!!!
        final OutgoingRpcRequest request = origMsg.getRequest();
        final UUID dest = request.to;
        // find it:
        final FleaseLease fl = fleaseRunners.get(dest);
        if (fl == null) {
            // boo
            LOG.error("Request to non exist: " + dest);
            origMsg.reply(null);
            return;
        }

        messages.mark();
        messageCnt.inc();

        //LOG.debug("Forwarding message from {} to {}, contents: {}", request.from, request.to, request.message);
        // Construct and send a IncomingRpcRequest from the OutgoingRpcRequest.
        // There is absolutely no way to know who this is from at this point from the infrastructure.
        final IncomingRpcRequest newRequest = new IncomingRpcRequest(1, request.from, request.message);
        AsyncRequest.withOneReply(rpcFiber, fl.getIncomingChannel(), newRequest, new Callback<OutgoingRpcReply>() {
            @Override
            public void onMessage(OutgoingRpcReply msg) {
                // Translate the OutgoingRpcReply -> IncomingRpcReply.
                //LOG.debug("Forwarding reply message from {} back to {}, contents: {}", dest, request.to, msg.message);
                messages.mark();
                messageCnt.inc();
                IncomingRpcReply newReply = new IncomingRpcReply(msg.message, dest);
                origMsg.reply(newReply);
            }
        });
    }

    public void run() throws ExecutionException, InterruptedException {
        List<ListenableFuture<LeaseValue>> futures = new ArrayList<>();
        for (FleaseLease fl : fleaseRunners.values()) {
            futures.add(fl.getLease());
            Thread.sleep(100);
        }

        // now print them out in order:
        System.out.println("Get lease results in order:");
        for (FleaseLease fl : fleaseRunners.values()) {
            ListenableFuture<LeaseValue> lv = futures.get(0);
            futures.remove(0);
            System.out.print(fl.getId());
            System.out.print(" : ");
            try {
                System.out.println(lv.get());
            } catch (Throwable t) {
                System.out.println("ex: " + t);
            }
        }
    }

    public void dispose() {
        rpcFiber.dispose();
        for(FleaseLease fl : fleaseRunners.values()) {
            fl.dispose();
        }
        fiberPool.dispose();
    }

    public static void main(String []args) throws ExecutionException, InterruptedException {
        InRamSim sim = new InRamSim(3);
        ConsoleReporter reporter = ConsoleReporter.forRegistry(sim.metrics)
                .convertDurationsTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();

        reporter.start(1, TimeUnit.SECONDS);
        sim.run();
        sim.dispose();
        reporter.report();
        reporter.stop();
    }
}
