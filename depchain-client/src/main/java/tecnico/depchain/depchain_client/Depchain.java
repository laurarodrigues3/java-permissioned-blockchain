package tecnico.depchain.depchain_client;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKey;

import tecnico.depchain.depchain_common.broadcasts.BestEffortBroadcast;
import tecnico.depchain.depchain_common.messages.ConfirmMessage;
import tecnico.depchain.depchain_common.messages.StringMessage;

public class Depchain {
	private BestEffortBroadcast broadcast;
	private Set<Long> pendingMessages = new HashSet<>();

	public Depchain(List<InetSocketAddress> locals, SecretKey ownKey, List<InetSocketAddress> remotes, List<SecretKey> remoteKeys)
		throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		broadcast = new BestEffortBroadcast(this::rxHandler, this::rxHandler, locals, ownKey, remotes, remoteKeys);
	}

	public boolean AppendString(String content) {
		StringMessage msg = new StringMessage(content);
		Long seqNum = msg.getSeqNum();
		synchronized (pendingMessages)
		{ pendingMessages.add(seqNum); }

		broadcast.broadcast(msg.serialize());

		//HACK: Waits until confirmation arrives
		//Stuck forever if it never does

		//TODO: Handle differently the accepted value of the ConfirmMessage

		//Wait for reply msg
		do {
			try
			{ pendingMessages.wait(); }
			catch (InterruptedException e)
			{ /* Ignore */ }
		} while (pendingMessages.contains(seqNum));

		return true;
	}

	private void rxHandler(byte[] data, InetSocketAddress remote) {
		//HACK: Assumes all incoming messages are ConfirmMessage

		ConfirmMessage msg = ConfirmMessage.deserialize(data);
		synchronized (pendingMessages)
		{ pendingMessages.remove(msg.getSeqNum()); }
		pendingMessages.notifyAll();
	}
}
