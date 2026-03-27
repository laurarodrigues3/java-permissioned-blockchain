package tecnico.depchain.depchain_server.blockchain;

import java.io.Serializable;

/**
 * Wrapper that holds a {@link Transaction} together with its Ed25519 signature bytes.
 * Stored in the Mempool so that the signature can be referenced later if needed
 * (e.g., for non-repudiation proofs in the block).
 */
public class MempoolEntry implements Serializable {
	private final Transaction transaction;
	private final byte[] signature;

	public MempoolEntry(Transaction transaction, byte[] signature) {
		this.transaction = transaction;
		this.signature = signature;
	}

	public Transaction getTransaction() { return transaction; }
	public byte[] getSignature() { return signature; }
}
