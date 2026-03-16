package tecnico.depchain.depchain_common.broadcasts;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import tecnico.depchain.depchain_common.DepchainUtils;

public class EagerReliableBroadcast extends MultiLinkBroadcast {
	private BestEffortBroadcast lower;
	private Set<Long> delivered = new HashSet<>();

	public EagerReliableBroadcast(
			BiConsumer<byte[], InetSocketAddress> rxHandler, BiConsumer<byte[], InetSocketAddress> brdHandler,
			List<InetSocketAddress> locals, PrivateKey ownKey,
			List<InetSocketAddress> remotes, List<PublicKey> remoteKeys)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		super(rxHandler, brdHandler);

		lower = new BestEffortBroadcast(this::rxHandler, this::brdHandler, locals, ownKey, remotes, remoteKeys);
	}

	@Override
	public void transmit(int link, byte[] data) throws IndexOutOfBoundsException {
		lower.transmit(link, data);
	}

	@Override
	public void broadcast(byte[] data) {
		lower.broadcast(data);
	}

	private void rxHandler(byte[] data, InetSocketAddress remote) {
		rxHandler.accept(data, remote);
	}

	private void brdHandler(byte[] data, InetSocketAddress remote) {
		Long digest = DepchainUtils.longDigest(data);

		if (delivered.contains(digest))
			return; // Ignore if already seen

		lower.broadcast(data);
		brdHandler.accept(data, remote);
	}

	@Override
	public void close() {
		lower.close();
	}
}
