package tecnico.depchain.depchain_server.blockchain;

import java.math.BigInteger;
import java.security.PublicKey;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import tecnico.depchain.depchain_common.DepchainClient;
import tecnico.depchain.depchain_common.Membership;
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
		// 1. Non-zero gas
		if (msg.getGasLimit() <= 0) {
			System.err.println("[Validator] REJECTED: gasLimit <= 0");
			return false;
		}
		BigInteger gasPrice;
		try {
			gasPrice = new BigInteger(msg.getGasPrice());
		} catch (NumberFormatException e) {
			System.err.println("[Validator] REJECTED: invalid gasPrice format");
			return false;
		}
		if (gasPrice.compareTo(BigInteger.ZERO) <= 0) {
			System.err.println("[Validator] REJECTED: gasPrice <= 0");
			return false;
		}

		// 2. Client identity
		int clientId = msg.getClientId();
		DepchainClient[] clients = Membership.getClients();
		if (clientId < 0 || clientId >= clients.length) {
			System.err.println("[Validator] REJECTED: invalid clientId " + clientId);
			return false;
		}
		PublicKey clientKey = clients[clientId].getPublicKey();
		String evmAddrHex = clients[clientId].getEvmAddress();

		// 2.5 Pre-parse all payload fields to prevent crashing from Byzantine payloads (NumberFormatException / IllegalArgumentException)
		Address fromAddr;
		BigInteger txValueInt;
		try {
			if (msg.getFrom() == null || msg.getFrom().isEmpty()) {
				System.err.println("[Validator] REJECTED: missing from address");
				return false;
			}
			fromAddr = Address.fromHexString(msg.getFrom());

			if (msg.getTo() != null && !msg.getTo().isEmpty()) {
				Address.fromHexString(msg.getTo()); // Dry-run to validate format
			}

			txValueInt = (msg.getValue() == null || msg.getValue().isEmpty()) ?
					BigInteger.ZERO : new BigInteger(msg.getValue());

			if (msg.getData() != null && !msg.getData().isEmpty()) {
				org.apache.tuweni.bytes.Bytes.fromHexString(msg.getData()); // Dry-run to validate format
			}
		} catch (Exception e) {
			System.err.println("[Validator] REJECTED: malformed payload format");
			return false;
		}

		// 3. Signature & Identity verification (Ed25519)
		if (evmAddrHex != null && !evmAddrHex.isEmpty()) {
			Address clientAddress = Address.fromHexString(evmAddrHex);
			if (!clientAddress.equals(fromAddr)) {
				System.err.println("[Validator] REJECTED: Sender address does not match clientId's registered address!");
				return false;
			}
		}

		if (!msg.verify(clientKey)) {
			System.err.println("[Validator] REJECTED: invalid Ed25519 signature for clientId " + clientId);
			return false;
		}

		// 4. Nonce check — must equal the pending nonce (no gaps, no replays)
		BigInteger expectedNonce = mempool.getPendingNonce(fromAddr);
		if (msg.getNonce() == null || !msg.getNonce().equals(expectedNonce)) {
			System.err.println("[Validator] REJECTED: nonce mismatch for " + msg.getFrom()
					+ " (expected " + expectedNonce + ", got " + msg.getNonce() + ")");
			return false;
		}

		// 5. Balance check — pending balance must cover gasPrice × gasLimit + value
		Wei gasPriceWei = Wei.of(gasPrice);
		Wei gasCost = gasPriceWei.multiply(Wei.of(msg.getGasLimit()));
		
		Wei totalCost = gasCost.add(Wei.of(txValueInt));
		
		Wei pendingBalance = mempool.getPendingBalance(fromAddr);

		if (pendingBalance.lessThan(totalCost)) {
			System.err.println("[Validator] REJECTED: insufficient pending balance for " + msg.getFrom()
					+ " (need " + totalCost + ", have " + pendingBalance + ")");
			return false;
		}

		System.out.println("[Validator] ACCEPTED tx from " + msg.getFrom() + " nonce=" + msg.getNonce());
		return true;
	}

	/**
	 * Converts a validated {@link TransactionMessage} into a {@link Transaction} record
	 * suitable for Mempool storage and later EVM execution.
	 */
	public static Transaction toTransaction(TransactionMessage msg) {
		return new Transaction(
				msg.getNonce(),
				Address.fromHexString(msg.getFrom()),
				msg.getTo() != null ? Address.fromHexString(msg.getTo()) : null,
				Wei.of(new BigInteger(msg.getGasPrice())),
				Wei.ZERO,  // maxPriorityFeePerGas (not used)
				Wei.ZERO,  // maxFeePerGas (not used)
				msg.getGasLimit(),
				(msg.getValue() != null && !msg.getValue().isEmpty()) ? Wei.of(new BigInteger(msg.getValue())) : Wei.ZERO,
				msg.getData() != null && !msg.getData().isEmpty()
						? org.apache.tuweni.bytes.Bytes.fromHexString(msg.getData())
						: org.apache.tuweni.bytes.Bytes.EMPTY,
				BigInteger.ZERO,  // v (not used — Ed25519 replaces secp256k1)
				BigInteger.ZERO,  // r
				BigInteger.ZERO   // s
		);
	}
}
