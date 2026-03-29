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

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import tecnico.depchain.depchain_common.blockchain.SignedTransaction;
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
		// Signing is now done inside createTransfer/createContractCall via SignedTransaction.signTransaction().
		// submitTransaction only broadcasts the already-signed message.
		long seqNum = tx.getSeqNum();

		synchronized (pendingMessages) {
			pendingMessages.put(seqNum, RequestStatus.SENT);
			confirmations.put(seqNum, new java.util.HashSet<>());
		}

		long timeoutMs = 2000; // 2 seconds before retrying
		int maxRetries = 5;
		int attempts = 0;

		System.out.println("[Depchain Client] Submitted tx seqNum=" + seqNum
				+ " from=" + tx.getSignedTransaction().tx().from() + " nonce=" + tx.getSignedTransaction().tx().nonce());

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
	 * Creates a Transaction record, signs it with Ed25519 via SignedTransaction,
	 * wraps it in a TransactionMessage, and auto-increments the local nonce.
	 *
	 * @param to       Recipient hex address "0x..."
	 * @param value    Amount in Wei (decimal string)
	 * @param gasLimit Gas limit for this transaction
	 * @param gasPrice Gas price in Wei (decimal string)
	 * @return A ready-to-submit TransactionMessage (already signed)
	 */
	public TransactionMessage createTransfer(String to, String value, long gasLimit, String gasPrice) {
		if (ownAddress == null) throw new IllegalStateException("Call setOwnAddress() before creating transactions");

		// Build the Transaction record with Besu types (now lives in depchain-common)
		Transaction tx = new Transaction(
				currentNonce,
				Address.fromHexString(ownAddress),
				Address.fromHexString(to),
				Wei.of(new BigInteger(gasPrice)),
				Wei.ZERO,  // maxPriorityFeePerGas (unused)
				Wei.ZERO,  // maxFeePerGas (unused)
				gasLimit,
				Wei.of(new BigInteger(value)),
				Bytes.EMPTY);  // no calldata for plain transfers

		// Sign the transaction with Ed25519, producing a SignedTransaction
		SignedTransaction signedTx = SignedTransaction.signTansaction(tx, ownKey);

		// Wrap in transport message with clientId and seqNum
		TransactionMessage msg = new TransactionMessage(clientId, signedTx);

		// Nonce is incremented optimistically at creation time to allow pipelining
		// multiple concurrent transactions. Use syncNonceWithServer() if desync occurs.
		this.currentNonce = this.currentNonce.add(BigInteger.ONE);
		return msg;
	}

	/**
	 * Convenience builder for a smart contract call.
	 * Creates a Transaction record with the ABI-encoded calldata, signs it,
	 * and auto-increments the local nonce.
	 *
	 * @param contractAddress Contract hex address "0x..."
	 * @param callData        ABI-encoded function call (hex string "0x...")
	 * @param gasLimit        Gas limit for this transaction
	 * @param gasPrice        Gas price in Wei (decimal string)
	 * @return A ready-to-submit TransactionMessage (already signed)
	 */
	public TransactionMessage createContractCall(String contractAddress, String callData, long gasLimit, String gasPrice) {
		if (ownAddress == null) throw new IllegalStateException("Call setOwnAddress() before creating transactions");

		// Build the Transaction record: value=0 for contract calls, data holds the ABI payload
		Transaction tx = new Transaction(
				currentNonce,
				Address.fromHexString(ownAddress),
				Address.fromHexString(contractAddress),
				Wei.of(new BigInteger(gasPrice)),
				Wei.ZERO,  // maxPriorityFeePerGas (unused)
				Wei.ZERO,  // maxFeePerGas (unused)
				gasLimit,
				Wei.ZERO,  // no value transfer for contract calls
				Bytes.fromHexString(callData));

		SignedTransaction signedTx = SignedTransaction.signTansaction(tx, ownKey);
		TransactionMessage msg = new TransactionMessage(clientId, signedTx);

		this.currentNonce = this.currentNonce.add(BigInteger.ONE);
		return msg;
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

