package tecnico.depchain.depchain_server.blockchain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import tecnico.depchain.depchain_common.blockchain.SignedTransaction;
import tecnico.depchain.depchain_common.blockchain.Transaction;

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

	//FIXME: Should allow to receive multiple transactions with same nonce, gas will sort them, once one is executed, the other is automatically reject by the runner
	// sender → (nonce → entry), TreeMap keeps nonces sorted ascending
	private final Map<Address, TreeMap<Long, SignedTransaction>> senderQueues = new HashMap<>();

	public Mempool() { }

	// ── Core Operations ─────────────────────────────────────────────────

	/**
	 * Adds a validated transaction to the mempool.
	 * Updates pending nonce and balance for the sender.
	 *
	 * @param tx        The validated transaction
	 * @param signature The Ed25519 signature bytes from the TransactionMessage
	 */
	public synchronized void addTransaction(SignedTransaction signedTx) {
		Transaction tx = signedTx.tx();
		Address sender = signedTx.tx().from();

		// Get or create sender queue
		TreeMap<Long, SignedTransaction> queue = senderQueues.computeIfAbsent(sender, k -> new TreeMap<>());
		queue.put(tx.nonce(), signedTx);

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
		Map<Address, TreeMap<Long, SignedTransaction>> workQueues = new HashMap<>();
		for (var entry : senderQueues.entrySet()) {
			workQueues.put(entry.getKey(), new TreeMap<>(entry.getValue()));
		}

		while (cumulativeGas < maxGasLimit) {
			// Find the head with highest gasPrice across all senders
			Address bestSender = null;
			SignedTransaction bestEntry = null;
			Wei bestGasPrice = Wei.ZERO;

			for (var entry : workQueues.entrySet()) {
				TreeMap<Long, SignedTransaction> queue = entry.getValue();
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
			TreeMap<Long, SignedTransaction> queue = senderQueues.get(tx.from());
			if (queue != null) {
				queue.remove(tx.nonce());
				if (queue.isEmpty()) {
					senderQueues.remove(tx.from());
				}
			}
		}

		System.out.println("[Mempool] Post-block cleanup done. Remaining txs: " + totalSize());
	}

	/**
	 * Returns the total number of pending transactions across all sender queues.
	 */
	public synchronized int totalSize() {
		int total = 0;
		for (TreeMap<Long, SignedTransaction> queue : senderQueues.values()) {
			total += queue.size();
		}
		return total;
	}
}
