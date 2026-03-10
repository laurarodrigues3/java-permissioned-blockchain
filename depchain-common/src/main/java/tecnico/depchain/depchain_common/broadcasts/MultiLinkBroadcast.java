package tecnico.depchain.depchain_common.broadcasts;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

public abstract class MultiLinkBroadcast {
	protected BiConsumer<byte[], InetSocketAddress> rxHandler;
	protected BiConsumer<byte[], InetSocketAddress> brdHandler;

	public MultiLinkBroadcast(BiConsumer<byte[], InetSocketAddress> rxHandler, BiConsumer<byte[], InetSocketAddress> brdHandler) {
		this.rxHandler = rxHandler;
		this.brdHandler = brdHandler;
	}

	public abstract void transmit(int link, byte[] data);

	public abstract void broadcast(byte[] data);

	public abstract void close();
}
