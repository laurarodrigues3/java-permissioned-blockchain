package tecnico.depchain.links;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

public abstract class P2PLink {
	public InetSocketAddress local;
	public InetSocketAddress remote;

	public P2PLink(InetSocketAddress local, InetSocketAddress remote) {
		assert local != null;
		assert remote != null;
		this.local = local;
		this.remote = remote;
	}

	public InetSocketAddress getRemote() {
		return remote;
	}

	public BiConsumer<byte[], P2PLink> rxHandler = null;

	public abstract void Transmit(byte[] data);
}
