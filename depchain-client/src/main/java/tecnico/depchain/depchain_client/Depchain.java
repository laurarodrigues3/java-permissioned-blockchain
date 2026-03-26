package tecnico.depchain.depchain_client;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tecnico.depchain.depchain_common.blockchain.Transaction;
import tecnico.depchain.depchain_common.broadcasts.BestEffortBroadcast;
import tecnico.depchain.depchain_common.messages.ConfirmMessage;
import tecnico.depchain.depchain_common.messages.TransactionMessage;

enum RequestStatus {
	SENT,
	ACCEPTED,
	REJECTED
}

public class Depchain {
	private BestEffortBroadcast broadcast;
	private Map<Long, RequestStatus> pendingMessages = new HashMap<>();
	private Map<Long, java.util.Set<InetSocketAddress>> confirmations = new HashMap<>();

	private int numReplicas;
	private int f;
	private int quorumSize;
	private int clientId;
	private PrivateKey ownKey;

	public Depchain(int clientId, List<InetSocketAddress> locals, PrivateKey ownKey, List<InetSocketAddress> remotes, List<PublicKey> remoteKeys)
		throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		this.clientId = clientId;
		this.ownKey = ownKey;
		broadcast = new BestEffortBroadcast(this::rxHandler, this::rxHandler, locals, ownKey, remotes, remoteKeys);
		this.numReplicas = remotes.size();
		this.f = (numReplicas - 1) / 3;
		// A client requires f+1 matching responses to guarantee at least 1 honest replica processed it
		this.quorumSize = f + 1;
	}

	public boolean AppendString(Transaction tx) {
		TransactionMessage msg = new TransactionMessage(clientId, tx);
		msg.sign(ownKey);
		Long seqNum = msg.getSeqNum();

		synchronized (pendingMessages) {
			pendingMessages.put(seqNum, RequestStatus.SENT);
			confirmations.put(seqNum, new java.util.HashSet<>());
		}

		long timeoutMs = 2000; // 2 seconds before retrying

		while (true) {
			broadcast.broadcast(msg.serialize());

			synchronized (pendingMessages) {
				try {
					pendingMessages.wait(timeoutMs);
				} catch (InterruptedException e) { /* Ignore */ }

				if (pendingMessages.get(seqNum) != RequestStatus.SENT) {
					break; // Reached ACCEPTED or REJECTED
				}
			}
			// If still SENT, loop continues and broadcasts again
			System.out.println("[Depchain Client] Timeout waiting for f+1 ConfirmMessages. Retrying seqNum=" + seqNum + "...");
		}

		boolean accepted;
		synchronized (pendingMessages) {
			accepted = pendingMessages.get(seqNum) == RequestStatus.ACCEPTED;
			pendingMessages.remove(seqNum);
			confirmations.remove(seqNum); // Clean up
		}

		return accepted;
	}

	private void rxHandler(byte[] data, InetSocketAddress remote) {
		//HACK: Assumes all incoming messages are ConfirmMessage
		ConfirmMessage msg = ConfirmMessage.deserialize(data);
		if (msg == null) return;

		Long seqNum = msg.getSeqNum();

		synchronized (pendingMessages) {
			if (pendingMessages.get(seqNum) == RequestStatus.SENT) {
				java.util.Set<InetSocketAddress> confs = confirmations.get(seqNum);
				if (confs != null) {
					confs.add(remote); // Track unique responders

					// If we get f+1 matching responses, we can safely accept/reject
					if (msg.getAccepted() && confs.size() >= quorumSize) {
						pendingMessages.replace(seqNum, RequestStatus.ACCEPTED);
						pendingMessages.notifyAll();
					} else if (!msg.getAccepted() && confs.size() >= quorumSize) {
						pendingMessages.replace(seqNum, RequestStatus.REJECTED);
						pendingMessages.notifyAll();
					}
				}
			}
		}
	}
}
