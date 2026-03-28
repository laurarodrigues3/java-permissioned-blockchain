package tecnico.depchain.depchain_client;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
	private String ownAddress; // Hex address "0x..." for the client's EOA

	// Auto-incrementing nonce for transaction ordering
	private BigInteger currentNonce = BigInteger.ZERO;

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

	/**
	 * Sets the hex address (e.g., "0x1111...") of this client's Externally Owned Account.
	 * Required for transaction construction.
	 */
	public void setOwnAddress(String address) {
		this.ownAddress = address;
	}

	/**
	 * Manually sets the current nonce.
	 * Useful for testing or when the client restarts and needs to sync with the network.
	 */
	public void setNonce(BigInteger nonce) {
		this.currentNonce = nonce;
	}

	/**
	 * Synchronizes the client's local nonce with the server's committed or pending state.
	 * Helps recover from desynchronization if a transaction was silently discarded.
	 */
	public void syncNonceWithServer(BigInteger serverNonce) {
		this.currentNonce = serverNonce;
	}

	/**
	 * Submits a signed transaction to all replicas (fire-and-forget).
	 * In this intermediate step, the method broadcasts the transaction and returns
	 * the seqNum for future tracking. There is no ConfirmMessage response yet —
	 * that will be wired in the next step when the Leader constructs blocks.
	 *
	 * @param tx The TransactionMessage to submit (will be signed automatically)
	 * @return The seqNum assigned to this submission
	 */
	public boolean submitTransaction(TransactionMessage tx) {
		//tx.sign(ownKey);
		//FIXME: Trash for now
		long seqNum = tx.getSeqNum();

		synchronized (pendingMessages) {
			pendingMessages.put(seqNum, RequestStatus.SENT);
			confirmations.put(seqNum, new java.util.HashSet<>());
		}

		long timeoutMs = 2000; // 2 seconds before retrying
		int maxRetries = 5;
		int attempts = 0;

		//System.out.println("[Depchain Client] Submitted tx seqNum=" + seqNum
		//		+ " from=" + tx.getFrom() + " nonce=" + tx.getNonce());

		while (attempts < maxRetries) {
			broadcast.broadcast(tx.serialize());
			attempts++;

			synchronized (pendingMessages) {
				try {
					pendingMessages.wait(timeoutMs);
				} catch (InterruptedException e) { /* Ignore */ }

				if (pendingMessages.get(seqNum) != RequestStatus.SENT) {
					break; // Reached ACCEPTED or REJECTED
				}
			}
			if (attempts < maxRetries) {
				System.out.println("[Depchain Client] Timeout waiting for f+1 ConfirmMessages. Retrying tx seqNum=" + seqNum + "...");
			} else {
				System.err.println("[Depchain Client] Max retries reached for tx seqNum=" + seqNum + ". Transaction failed.");
			}
		}

		boolean accepted;
		synchronized (pendingMessages) {
			accepted = pendingMessages.get(seqNum) == RequestStatus.ACCEPTED;
			pendingMessages.remove(seqNum);
			confirmations.remove(seqNum); // Clean up
		}

		return accepted;
	}

	/**
	 * Convenience builder for a DepCoin transfer transaction.
	 * Auto-increments the nonce.
	 *
	 * @param to       Recipient hex address "0x..."
	 * @param value    Amount in Wei (decimal string)
	 * @param gasLimit Gas limit for this transaction
	 * @param gasPrice Gas price in Wei (decimal string)
	 * @return A ready-to-submit TransactionMessage
	 */
	public TransactionMessage createTransfer(String to, String value, long gasLimit, String gasPrice) {
		//if (ownAddress == null) throw new IllegalStateException("Call setOwnAddress() before creating transactions");
		//TransactionMessage tx = new TransactionMessage(
		//		clientId, currentNonce, ownAddress, to, gasPrice, gasLimit, value, "");
		//this.currentNonce = this.currentNonce.add(BigInteger.ONE);
		//return tx;
		return null; //FIXME: Trash for now
	}

	/**
	 * Convenience builder for a smart contract call.
	 *
	 * @param contractAddress Contract hex address "0x..."
	 * @param callData        ABI-encoded function call (hex string "0x...")
	 * @param gasLimit        Gas limit for this transaction
	 * @param gasPrice        Gas price in Wei (decimal string)
	 * @return A ready-to-submit TransactionMessage
	 */
	public TransactionMessage createContractCall(String contractAddress, String callData, long gasLimit, String gasPrice) {
		//if (ownAddress == null) throw new IllegalStateException("Call setOwnAddress() before creating transactions");
		//TransactionMessage tx = new TransactionMessage(
		//		clientId, currentNonce, ownAddress, contractAddress, gasPrice, gasLimit, "0", callData);
		//this.currentNonce = this.currentNonce.add(BigInteger.ONE);
		//return tx;
		return null; //FIXME: Trash for now
	}

	// ── Network Handler ─────────────────────────────────────────────────

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

