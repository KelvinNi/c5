/*
 * Copyright 2014 WANdisco
 *
 *  WANdisco licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package c5db;

import c5db.client.SingleNodeTableInterface;
import c5db.client.generated.Condition;
import c5db.client.generated.MutateRequest;
import c5db.client.generated.MutationProto;
import c5db.client.generated.RegionSpecifier;
import c5db.client.generated.Response;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.hadoop.hbase.util.Bytes;
import org.jetlang.fibers.Fiber;
import org.jetlang.fibers.ThreadFiber;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ITAsyncWrite extends ClusterOrPseudoCluster {

  private static final int TO_SEND = 100000;
  CountDownLatch countDownLatch = new CountDownLatch(TO_SEND);

  @Test
  public void writeOutAsynchronously() throws ExecutionException, InterruptedException, TimeoutException {
    RegionSpecifier regionSpecifier = new RegionSpecifier(RegionSpecifier.RegionSpecifierType.REGION_NAME,
        ByteBuffer.wrap(Bytes.toBytes("c5:writeOutAsynchronously")));
    ByteBuffer cq = ByteBuffer.wrap(Bytes.toBytes("cq"));
    ByteBuffer cf = ByteBuffer.wrap(Bytes.toBytes("cf"));
    ByteBuffer value = ByteBuffer.wrap(new byte[512]);
    MutationProto.ColumnValue.QualifierValue qualifierValue = new MutationProto.ColumnValue.QualifierValue(cq,
        value,
        0l,
        null);

    SingleNodeTableInterface singleNodeTable = new SingleNodeTableInterface("localhost", getRegionServerPort());

    Fiber flusher = new ThreadFiber();
    flusher.start();
    flusher.scheduleAtFixedRate(singleNodeTable::flushHandler, 50, 50, MILLISECONDS);

    for (int i = 0; i != TO_SEND; i++) {
      sendProto(regionSpecifier, cf, Arrays.asList(qualifierValue), singleNodeTable, i);
    }
    System.out.println("buffered");
    countDownLatch.await();
  }

  private void sendProto(RegionSpecifier regionSpecifier,
                         ByteBuffer cf,
                         List<MutationProto.ColumnValue.QualifierValue> qualifierValue,
                         SingleNodeTableInterface singleNodeTable,
                         int row) throws ExecutionException, InterruptedException {
    MutationProto mutationProto = new MutationProto(ByteBuffer.wrap(Bytes.toBytes(row)),
        MutationProto.MutationType.PUT,
        Arrays.asList(new MutationProto.ColumnValue(cf, qualifierValue)),
        0l,
        new ArrayList<>(),
        MutationProto.Durability.FSYNC_WAL,
        null,
        1);
    MutateRequest mutateRequest = new MutateRequest(regionSpecifier, mutationProto, new Condition());

    ListenableFuture<Response> future = singleNodeTable.bufferMutate(mutateRequest);

    Futures.addCallback(future, new FutureCallback<Response>() {
      @Override
      public void onSuccess(Response result) {
        countDownLatch.countDown();
      }

      @Override
      public void onFailure(Throwable t) {
        t.toString();
      }
    });

  }

  public static void main(String[] args) throws InterruptedException, ExecutionException, TimeoutException {
    ITAsyncWrite asyncWrite = new ITAsyncWrite();
    asyncWrite.writeOutAsynchronously();
  }
}
