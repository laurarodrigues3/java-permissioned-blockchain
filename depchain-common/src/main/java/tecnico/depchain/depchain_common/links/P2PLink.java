package tecnico.depchain.depchain_common.links;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

public abstract class P2PLink {
	protected BiConsumer<byte[], InetSocketAddress> rxHandler;

	public P2PLink(BiConsumer<byte[], InetSocketAddress> rxHandler) {
		this.rxHandler = rxHandler;
	}

	public abstract void transmit(byte[] data);

	public abstract void close();
}
