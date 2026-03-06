package tecnico.depchain.broadcasts;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import javax.crypto.SecretKey;

import tecnico.depchain.links.AuthenticatedPerfectLink;
import tecnico.depchain.links.P2PLink;

public class BestEffortBroadcast extends MultiLinkBroadcast {
	private List<P2PLink> links;
	private static final byte P2P_PREFIX = 0;
	private static final byte BROADCAST_PREFIX = 1;

	public BestEffortBroadcast(
			BiConsumer<byte[], InetSocketAddress> rxHandler, BiConsumer<byte[], InetSocketAddress> brdHandler,
			InetSocketAddress local, SecretKey ownKey,
			List<InetSocketAddress> remotes, List<SecretKey> remoteKeys)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		super(rxHandler, brdHandler);

		if (remotes.size() != remoteKeys.size())
			throw new IllegalArgumentException();

		links = new ArrayList<P2PLink>(remotes.size());
		for (int i = 0; i < remotes.size(); ++i)
			links.add(new AuthenticatedPerfectLink(this::rxHandlerFunc, local, remotes.get(i), ownKey, remoteKeys.get(i)));
	}

	@Override
	public void transmit(int link, byte[] data) throws IndexOutOfBoundsException {
		// Prepend message type
		byte[] msg = new byte[1 + data.length];
		msg[0] = P2P_PREFIX;
		System.arraycopy(data, 0, msg, 1, data.length);

		links.get(link).transmit(msg);
	}

	@Override
	public void broadcast(byte[] data) {
		// Prepend message type
		byte[] msg = new byte[1 + data.length];
		msg[0] = BROADCAST_PREFIX;
		System.arraycopy(data, 0, msg, 1, data.length);

		links.forEach((P2PLink link) -> {
			link.transmit(msg);
		});
	}

	private void rxHandlerFunc(byte[] data, InetSocketAddress remote) {
		// Pop message type
		byte type = data[0];

		switch (type)
		{
		case P2P_PREFIX:
			rxHandler.accept(data, remote);
			break;

		case BROADCAST_PREFIX:
			// Simply deliver
			brdHandler.accept(data, remote);
			break;
		}
	}
}
