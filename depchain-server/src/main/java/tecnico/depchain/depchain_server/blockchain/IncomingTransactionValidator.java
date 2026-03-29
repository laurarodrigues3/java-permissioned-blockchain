package tecnico.depchain.depchain_server.blockchain;

import java.math.BigInteger;
import java.security.PublicKey;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

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
 *   <li><b>Client identity</b>: clientId must map to a valid client in Membership</li>
 *   <li><b>Address match</b>: tx.from() must match the registered EVM address for the clientId</li>
 *   <li><b>Signature</b>: Ed25519 signature on the serialized Transaction bytes</li>
 *   <li><b>Non-zero gas</b>: gasLimit &gt; 0 and gasPrice &gt; 0</li>
 *   <li><b>Nonce</b>: tx.nonce must equal the pending nonce (no gaps, no replays)</li>
 *   <li><b>Balance</b>: pending balance must cover gasPrice * gasLimit + value</li>
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
		// Extract the Transaction from the SignedTransaction envelope
		Transaction tx = msg.getSignedTransaction().tx();

		// 1. Client identity — clientId must map to a valid Membership entry
		int clientId = msg.getClientId();
		DepchainClient[] clients = Membership.getClients();
		if (clientId < 0 || clientId >= clients.length) {
			System.err.println("[Validator] REJECTED: invalid clientId " + clientId);
			return false;
		}
		PublicKey clientKey = clients[clientId].getPublicKey();
		String evmAddrHex = clients[clientId].getEvmAddress();

		// 2. Identity verification — the 'from' address in the Transaction must match
		//    the EVM address registered for this clientId (prevents spoofing attacks)
		if (evmAddrHex != null && !evmAddrHex.isEmpty()) {
			Address clientAddress = Address.fromHexString(evmAddrHex);
			if (!clientAddress.equals(tx.from())) {
				System.err.println("[Validator] REJECTED: Sender address does not match clientId's registered address!");
				return false;
			}
		}

		// 3. Signature check — Ed25519 signature over the canonical serialized Transaction bytes
		if (!msg.getSignedTransaction().verify(clientKey)) {
			System.err.println("[Validator] REJECTED: invalid Ed25519 signature for clientId " + clientId);
			return false;
		}

		// 4. Non-zero gas — both gasLimit and gasPrice must be positive
		if (tx.gasLimit() <= 0) {
			System.err.println("[Validator] REJECTED: gasLimit <= 0");
			return false;
		}
		// gasPrice check was removed during refactoring — re-added to prevent zero-fee spam
		if (tx.gasPrice().compareTo(Wei.ZERO) <= 0) {
			System.err.println("[Validator] REJECTED: gasPrice <= 0");
			return false;
		}

		// 5. Nonce check — must equal the pending nonce (no gaps, no replays)
		BigInteger expectedNonce = mempool.getPendingNonce(tx.from());
		if (!tx.nonce().equals(expectedNonce)) {
			System.err.println("[Validator] REJECTED: nonce mismatch for " + tx.from()
					+ " (expected " + expectedNonce + ", got " + tx.nonce() + ")");
			return false;
		}

		// 6. Balance check — pending balance must cover gasCost + transferred value
		//    This prevents Double Spend attacks where a sender submits many transactions
		//    that individually pass but collectively exceed their balance.
		//    (This check was removed during Diogo's refactoring and re-added here)
		Wei gasCost = tx.gasPrice().multiply(Wei.of(tx.gasLimit()));
		Wei totalCost = gasCost.add(tx.value());
		Wei pendingBalance = mempool.getPendingBalance(tx.from());

		if (pendingBalance.lessThan(totalCost)) {
			System.err.println("[Validator] REJECTED: insufficient pending balance for " + tx.from()
					+ " (need " + totalCost + ", have " + pendingBalance + ")");
			return false;
		}

		System.out.println("[Validator] ACCEPTED tx from " + tx.from() + " nonce=" + tx.nonce());
		return true;
	}
}
