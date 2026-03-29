package tecnico.depchain.depchain_server.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;

import tecnico.depchain.depchain_common.blockchain.SignedTransaction;
import tecnico.depchain.depchain_common.blockchain.Transaction;
import tecnico.depchain.depchain_common.messages.TransactionMessage;

/**
 * Transaction pool with per-sender nonce ordering and global gasPrice selection.
 *
 * <h3>Data Structure</h3>
 * Each sender has its own {@link TreeMap} of transactions keyed by nonce (ascending).
 * The <b>head</b> (lowest nonce) of each sender queue is the only candidate eligible
 * for global gasPrice competition.
 *
 * <h3>Pending State</h3>
 * Maintains pending nonces and balances independently from the committed EVM state,
 * so that multiple transactions from the same sender can be accepted before a block
 * is committed.
 *
 * <h3>Block Construction Algorithm</h3>
 * {@link #getTopTransactions(long)} iterates: among all sender-queue heads, picks the
 * one with the highest gasPrice. Once picked, the next nonce of that sender becomes
 * the new head. This guarantees nonce ordering per-sender while maximizing gas revenue.
 */
public class Mempool {

	// sender → (nonce → entry), TreeMap keeps nonces sorted ascending
	private final Map<Address, TreeMap<BigInteger, SignedTransaction>> senderQueues = new HashMap<>();

	// Pending state: tracks what the "next" nonce and available balance would be
	// if all currently queued transactions were executed.
	private final Map<Address, BigInteger> pendingNonces = new HashMap<>();
	private final Map<Address, Wei> pendingBalances = new HashMap<>();

	public Mempool() { }

	// ── Core Operations ─────────────────────────────────────────────────

	/**
	 * Atomically validates and adds a transaction to the mempool.
	 * This prevents race conditions where multiple UDP receiver threads pass validation
	 * before any of them deducts the pending balance.
	 */
	public synchronized boolean submitTransaction(TransactionMessage msg, IncomingTransactionValidator validator) {
		if (!validator.validate(msg)) {
			return false;
		}
		addTransaction(msg.getSignedTransaction());
		return true;
	}

	/**
	 * Adds a validated transaction to the mempool.
	 * Updates pending nonce and balance for the sender.
	 * <p>
	 * <b>Precondition:</b> The transaction must have already passed
	 * {@link IncomingTransactionValidator} checks.
	 *
	 * @param tx        The validated transaction
	 * @param signature The Ed25519 signature bytes from the TransactionMessage
	 */
	public synchronized void addTransaction(SignedTransaction signedTx) {
		Transaction tx = signedTx.tx();
		Address sender = signedTx.tx().from();

		// Get or create sender queue
		TreeMap<BigInteger, SignedTransaction> queue = senderQueues.computeIfAbsent(sender, k -> new TreeMap<>());
		queue.put(tx.nonce(), signedTx);

		// Update pending nonce (advance to nonce + 1)
		BigInteger nextNonce = tx.nonce().add(BigInteger.ONE);
		BigInteger currentPending = pendingNonces.get(sender);
		if (currentPending == null || nextNonce.compareTo(currentPending) > 0) {
			pendingNonces.put(sender, nextNonce);
		}

		// Update pending balance (subtract gasPrice × gasLimit + value)
		Wei gasCost = tx.gasPrice().multiply(Wei.of(tx.gasLimit()));
		Wei totalCost = gasCost.add(tx.value());
		Wei currentBalance = pendingBalances.get(sender);
		if (currentBalance == null) {
			currentBalance = getCommittedBalance(sender);
		}
		pendingBalances.put(sender, currentBalance.subtract(totalCost));

		System.out.println("[Mempool] Added tx from " + sender.toHexString()
				+ " nonce=" + tx.nonce() + " gasPrice=" + tx.gasPrice()
				+ " | Queue size: " + totalSize());
	}

	/**
	 * Selects transactions for block construction using the dual-ordering algorithm:
	 * <ol>
	 *   <li>Among all sender queue heads (lowest nonce per sender), pick the one with highest gasPrice.</li>
	 *   <li>Add it to the result. The next nonce of that sender becomes the new head.</li>
	 *   <li>Repeat until maxGasLimit is reached or no candidates remain.</li>
	 * </ol>
	 *
	 * @param maxGasLimit Maximum cumulative gas for the block
	 * @return Ordered list of transactions ready for EVM execution
	 */
	public synchronized List<Transaction> getTopTransactions(long maxGasLimit) {
		List<Transaction> result = new ArrayList<>();
		long cumulativeGas = 0;

		// Work on a deep copy of the queue structure so this is non-destructive
		Map<Address, TreeMap<BigInteger, SignedTransaction>> workQueues = new HashMap<>();
		for (var entry : senderQueues.entrySet()) {
			workQueues.put(entry.getKey(), new TreeMap<>(entry.getValue()));
		}

		while (cumulativeGas < maxGasLimit) {
			// Find the head with highest gasPrice across all senders
			Address bestSender = null;
			SignedTransaction bestEntry = null;
			Wei bestGasPrice = Wei.ZERO;

			for (var entry : workQueues.entrySet()) {
				TreeMap<BigInteger, SignedTransaction> queue = entry.getValue();
				if (queue.isEmpty()) continue;

				SignedTransaction head = queue.firstEntry().getValue();
				Wei headGasPrice = head.tx().gasPrice();

				if (headGasPrice.compareTo(bestGasPrice) > 0) {
					bestSender = entry.getKey();
					bestEntry = head;
					bestGasPrice = headGasPrice;
				}
			}

			if (bestEntry == null) break; // No more candidates

			Transaction bestTx = bestEntry.tx();

			// Check if adding this tx would exceed the gas limit
			if (cumulativeGas + bestTx.gasLimit() > maxGasLimit) {
				// Remove the ENTIRE sender queue from consideration for this block.
				// We cannot skip a nonce, so no subsequent txs from this sender can be added.
				workQueues.remove(bestSender);
				continue;
			}

			result.add(bestTx);
			cumulativeGas += bestTx.gasLimit();

			// Remove the selected head → next nonce becomes the new head automatically
			workQueues.get(bestSender).pollFirstEntry();
		}

		return result;
	}

	// ── Pending State Queries ───────────────────────────────────────────

	/**
	 * Returns the next expected nonce for an address.
	 * If the address has pending transactions, returns the next nonce after the last pending tx.
	 * Otherwise returns the committed nonce from the EVM.
	 */
	public synchronized BigInteger getPendingNonce(Address address) {
		BigInteger pending = pendingNonces.get(address);
		if (pending != null) {
			return pending;
		}
		return getCommittedNonce(address);
	}

	/**
	 * Returns the available pending balance for an address.
	 * If the address has pending transactions, returns the balance after deducting
	 * all pending gas costs. Otherwise returns the committed balance from the EVM.
	 */
	public synchronized Wei getPendingBalance(Address address) {
		Wei pending = pendingBalances.get(address);
		if (pending != null) {
			return pending;
		}
		return getCommittedBalance(address);
	}

	// ── Post-Block Cleanup ──────────────────────────────────────────────

	/**
	 * Called after a block is committed (passes DECIDE + EVM execution).
	 * <ol>
	 *   <li>Removes all executed transactions from sender queues.</li>
	 *   <li>Resets pending state from the new EVM committed state.</li>
	 *   <li>Re-validates remaining pending transactions; evicts any that became invalid.</li>
	 * </ol>
	 */
	public synchronized void onBlockCommitted(List<Transaction> executedTxs) {
		// 1. Remove executed transactions
		for (Transaction tx : executedTxs) {
			TreeMap<BigInteger, SignedTransaction> queue = senderQueues.get(tx.from());
			if (queue != null) {
				queue.remove(tx.nonce());
				if (queue.isEmpty()) {
					senderQueues.remove(tx.from());
				}
			}
		}

		// 2. Reset all pending state from EVM committed state
		pendingNonces.clear();
		pendingBalances.clear();

		// 3. Re-validate remaining transactions per sender
		Iterator<Map.Entry<Address, TreeMap<BigInteger, SignedTransaction>>> senderIt = senderQueues.entrySet().iterator();
		while (senderIt.hasNext()) {
			Map.Entry<Address, TreeMap<BigInteger, SignedTransaction>> senderEntry = senderIt.next();
			Address sender = senderEntry.getKey();
			TreeMap<BigInteger, SignedTransaction> queue = senderEntry.getValue();

			BigInteger expectedNonce = getCommittedNonce(sender);
			Wei availableBalance = getCommittedBalance(sender);

			Iterator<Map.Entry<BigInteger, SignedTransaction>> txIt = queue.entrySet().iterator();
			while (txIt.hasNext()) {
				Map.Entry<BigInteger, SignedTransaction> txEntry = txIt.next();
				Transaction tx = txEntry.getValue().tx();

				// Check nonce continuity
				if (!tx.nonce().equals(expectedNonce)) {
					// Nonce gap or mismatch — evict this and all subsequent txs from this sender
					System.out.println("[Mempool] Evicting tx nonce=" + tx.nonce()
							+ " from " + sender.toHexString() + " (expected nonce " + expectedNonce + ")");
					txIt.remove();
					continue;
				}

				// Check balance
				Wei gasCost = tx.gasPrice().multiply(Wei.of(tx.gasLimit()));
				Wei totalCost = gasCost.add(tx.value());
				if (availableBalance.lessThan(totalCost)) {
					System.out.println("[Mempool] Evicting tx nonce=" + tx.nonce()
							+ " from " + sender.toHexString() + " (insufficient balance)");
					txIt.remove();
					continue;
				}

				// Valid — update pending state
				expectedNonce = expectedNonce.add(BigInteger.ONE);
				availableBalance = availableBalance.subtract(totalCost);
			}

			if (queue.isEmpty()) {
				senderIt.remove();
			} else {
				// Update pending state for this sender
				pendingNonces.put(sender, expectedNonce);
				pendingBalances.put(sender, availableBalance);
			}
		}

		System.out.println("[Mempool] Post-block cleanup done. Remaining txs: " + totalSize());
	}

	// ── Size ────────────────────────────────────────────────────────────

	/**
	 * Returns the total number of pending transactions across all sender queues.
	 */
	public synchronized int totalSize() {
		int total = 0;
		for (TreeMap<BigInteger, SignedTransaction> queue : senderQueues.values()) {
			total += queue.size();
		}
		return total;
	}

	// ── Private Helpers ─────────────────────────────────────────────────

	private BigInteger getCommittedNonce(Address address) {
		Account account = EVM.getInstance().getUpdater().get(address);
		if (account == null) return BigInteger.ZERO;
		return BigInteger.valueOf(account.getNonce());
	}

	private Wei getCommittedBalance(Address address) {
		Account account = EVM.getInstance().getUpdater().get(address);
		if (account == null) return Wei.ZERO;
		return account.getBalance();
	}
}
