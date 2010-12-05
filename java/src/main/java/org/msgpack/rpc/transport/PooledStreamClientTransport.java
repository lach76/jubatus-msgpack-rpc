//
// MessagePack-RPC for Java
//
// Copyright (C) 2010 FURUHASHI Sadayuki
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
package org.msgpack.rpc.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.msgpack.rpc.Session;
import org.msgpack.rpc.config.StreamClientConfig;
import org.msgpack.MessagePack;

public abstract class PooledStreamClientTransport<Channel, PendingBuffer extends OutputStream> implements ClientTransport {
	private final Object lock = new Object();
	private final List<Channel> pool = new ArrayList<Channel>();
	private int reconnectionLimit;
	private int connecting = 0;

	protected final Session session;
	protected final StreamClientConfig config;

	public PooledStreamClientTransport(StreamClientConfig config, Session session) {
		this.session = session;
		this.config = config;
		this.reconnectionLimit = config.getReconnectionLimit();
	}

	protected Session getSession() {
		return session;
	}

	protected StreamClientConfig getConfig() {
		return config;
	}

	@Override
	public void sendMessage(Object msg) {
//System.out.println("sending message: "+msg);
		synchronized(lock) {
			if(connecting == -1) { return; }  // already closed
			if(pool.isEmpty()) {
				if(connecting == 0) {
					tryConnect(lock);
					connecting++;
				}
				try {
					MessagePack.pack(getPendingBuffer(), msg);
				} catch (IOException e) {
					// FIXME
				}
			} else {
				// FIXME pseudo connecting load balancing
				Channel c = pool.get(0);
				sendMessageChannel(c, msg);
			}
		}
	}

	@Override
	public void close() {
		synchronized(lock) {
			if(pendingBuffer != null) {
				resetPendingBuffer(pendingBuffer);
			}
			connecting = -1;
			for(Channel c : pool) {
				closeChannel(c);
			}
			pool.clear();
		}
	}

	public void onConnected(Channel c) {
//System.out.println("connected: "+c);
		synchronized(lock) {
			if(connecting == -1) { closeChannel(c); return; }  // already closed
			pool.add(c);
			if(pendingBuffer != null) {
				flushPendingBuffer(pendingBuffer, c);
			}
			connecting = 0;
		}
	}

	public void onConnectFailed(Throwable cause) {
//System.out.println("connect failed: "+cause);
		synchronized(lock) {
			if(connecting == -1) { return; }  // already closed
			if(connecting < reconnectionLimit) {
				tryConnect(lock);
				connecting++;
			} else {
				connecting = 0;
				if(pendingBuffer != null) {
					resetPendingBuffer(pendingBuffer);
				}
				session.transportConnectFailed();
			}
		}
	}

	public void onClosed(Channel c) {
//System.out.println("closed: "+c);
		synchronized(lock) {
			if(connecting == -1) { return; }  // already closed
			pool.remove(c);
		}
	}

	private void tryConnect(Object locked) {
		startConnection();
	}

	private PendingBuffer pendingBuffer = null;

	protected PendingBuffer getPendingBuffer() {
		if(pendingBuffer == null) {
			pendingBuffer = newPendingBuffer();
		}
		return pendingBuffer;
	}

	protected abstract PendingBuffer newPendingBuffer();
	protected abstract void resetPendingBuffer(PendingBuffer b);
	protected abstract void flushPendingBuffer(PendingBuffer b, Channel c);

	protected abstract void startConnection();

	protected abstract void sendMessageChannel(Channel c, Object msg);
	protected abstract void closeChannel(Channel c);
}

