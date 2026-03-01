package tecnico.depchain.links;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

public abstract class P2PLink {
	public InetSocketAddress remote;

	public P2PLink(InetSocketAddress remote) {
		assert remote != null;
		this.remote = remote;
	}

	public InetSocketAddress getRemote() {
		return remote;
	}

	public BiConsumer<byte[], P2PLink> rxHandler = null;

	public abstract void Transmit(byte[] data);
}
