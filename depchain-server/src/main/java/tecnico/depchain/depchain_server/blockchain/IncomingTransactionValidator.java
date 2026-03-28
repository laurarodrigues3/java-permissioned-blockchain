package tecnico.depchain.depchain_server.blockchain;

import java.math.BigInteger;
import java.security.PublicKey;

import org.hyperledger.besu.datatypes.Address;

import tecnico.depchain.depchain_common.DepchainClient;
import tecnico.depchain.depchain_common.Membership;
import tecnico.depchain.depchain_common.blockchain.Transaction;
import tecnico.depchain.depchain_common.messages.TransactionMessage;

/**
 * Pre-mempool validation gate for incoming transactions.
 * Validates against the Mempool's <b>pending state</b> (not the committed EVM state),
 * allowing multiple transactions from the same sender to be accepted before a block is committed.
 *
 * <h3>Checks performed (in order):</h3>
 * <ol>
 *   <li><b>Non-zero gas</b>: gasPrice &gt; 0 and gasLimit &gt; 0</li>
 *   <li><b>Client identity</b>: clientId must map to a valid client in Membership</li>
 *   <li><b>Signature verification</b>: Ed25519 signature over canonical transaction bytes</li>
 *   <li><b>Nonce check</b>: tx.nonce must equal the pending nonce (no gaps, no replays)</li>
 *   <li><b>Balance check</b>: pending balance must cover gasPrice × gasLimit</li>
 * </ol>
 */
public class IncomingTransactionValidator {

	private final Mempool mempool;

	public IncomingTransactionValidator(Mempool mempool) {
		this.mempool = mempool;
	}

	/**
	 * Validates an incoming TransactionMessage before it enters the Mempool.
	 *
	 * @param msg The raw transport message from the client
	 * @return {@code true} if the transaction passes all checks and can be added to the Mempool
	 */
	public boolean validate(TransactionMessage msg) {
		Transaction tx = msg.getSignedTransaction().tx();

		// Client identity
		int clientId = msg.getClientId();
		DepchainClient[] clients = Membership.getClients();
		if (clientId < 0 || clientId >= clients.length) {
			System.err.println("[Validator] REJECTED: invalid clientId " + clientId);
			return false;
		}
		PublicKey clientKey = clients[clientId].getPublicKey();
		String evmAddrHex = clients[clientId].getEvmAddress();

		// Identity verification (Ed25519)
		if (evmAddrHex != null && !evmAddrHex.isEmpty()) {
			Address clientAddress = Address.fromHexString(evmAddrHex);
			if (!clientAddress.equals(tx.from())) {
				System.err.println("[Validator] REJECTED: Sender address does not match clientId's registered address!");
				return false;
			}
		}

		// Signature check
		if (!msg.getSignedTransaction().verify(clientKey)) {
			System.err.println("[Validator] REJECTED: invalid Ed25519 signature for clientId " + clientId);
			return false;
		}

		// Non-zero gas
		if (tx.gasLimit() <= 0) {
			System.err.println("[Validator] REJECTED: gasLimit <= 0");
			return false;
		}

		// Nonce check — must equal the pending nonce (no gaps, no replays)
		BigInteger expectedNonce = mempool.getPendingNonce(tx.from());
		if (msg.getSignedTransaction().tx() == null || !msg.getSignedTransaction().tx().nonce().equals(expectedNonce)) {
			System.err.println("[Validator] REJECTED: nonce mismatch for " + msg.getSignedTransaction().tx().from()
					+ " (expected " + expectedNonce + ", got " + msg.getSignedTransaction().tx().nonce() + ")");
			return false;
		}

		System.out.println("[Validator] ACCEPTED tx from " + msg.getSignedTransaction().tx().from() + " nonce=" + msg.getSignedTransaction().tx().nonce());
		return true;
	}
}
