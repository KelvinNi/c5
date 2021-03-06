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

package c5db.client.codec;

import c5db.client.generated.Response;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.protostuff.ByteBufferInput;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class WebsocketProtostuffDecoder extends WebSocketClientProtocolHandler {

  private static final long HANDSHAKE_TIMEOUT = 4000;
  private final WebSocketClientHandshaker handShaker;
  private final SettableFuture<Boolean> handshakeFuture = SettableFuture.create();

  public WebsocketProtostuffDecoder(WebSocketClientHandshaker handShaker) {
    super(handShaker);
    this.handShaker = handShaker;
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof ClientHandshakeStateEvent) {
      final ClientHandshakeStateEvent clientHandshakeStateEvent = (ClientHandshakeStateEvent) evt;
      if (clientHandshakeStateEvent.equals(ClientHandshakeStateEvent.HANDSHAKE_COMPLETE)) {
        handshakeFuture.set(true);
      }
    }
    super.userEventTriggered(ctx, evt);
  }


  @Override
  protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) throws Exception {
    if (frame instanceof BinaryWebSocketFrame) {
      final ByteBufferInput input = new ByteBufferInput(frame.content().nioBuffer(), false);
      final Response newMsg = Response.getSchema().newMessage();
      Response.getSchema().mergeFrom(input, newMsg);
      out.add(newMsg);
    } else {
      super.decode(ctx, frame, out);
    }
  }

  public void syncOnHandshake() throws InterruptedException, ExecutionException, TimeoutException {
    while (!this.handShaker.isHandshakeComplete()) {
      handshakeFuture.get(HANDSHAKE_TIMEOUT, TimeUnit.MILLISECONDS);
    }
  }
}
