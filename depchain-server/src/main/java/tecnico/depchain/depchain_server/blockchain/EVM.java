package tecnico.depchain.depchain_server.blockchain;


import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import tecnico.depchain.depchain_common.blockchain.Transaction;

public class EVM {
	private static EVM singleton = null;

	private SimpleWorld world;
	private WorldUpdater updater;

	/**
	 * Tracks every address that has ever been created or touched in this EVM.
	 * Populated by: createEOA, createCA, and registerAddress (called during
	 * block/transaction execution for senders, receivers, and contract-created addresses).
	 */
	private final Set<Address> knownAddresses = new HashSet<>();

	private EVM() {
		world = new SimpleWorld();
		updater = world.updater();
	}

	public static EVM getInstance() {
		if (singleton == null)
			singleton = new EVM();
		return singleton;
	}

	/** Reset the singleton (useful for tests that need a clean EVM). */
	public static void resetInstance() {
		singleton = null;
	}

	public WorldUpdater getUpdater() {
		return updater;
	}

	/**
	 * Registers an address as known. Called by TransactionRunner and GenesisLoader
	 * to ensure dynamically created accounts (via transfers, CREATE, CREATE2) are
	 * tracked for world state snapshots.
	 */
	public void registerAddress(Address address) {
		knownAddresses.add(address);
	}

	public void createEOA(Address address, Wei balance) {
		MutableAccount eoa = updater.createAccount(address);
		eoa.setNonce(0);
		eoa.setBalance(balance);
		knownAddresses.add(address);
		updater.commit();
	}

	public void createCA(Address address, Bytes code) {
		MutableAccount ca = updater.createAccount(address);
		ca.setCode(code);
		ca.setNonce(1); //0 is contract creation
		ca.setBalance(Wei.ZERO);
		knownAddresses.add(address);
		updater.commit();
	}

	public boolean executeBlock(Block block, Address minter, boolean commit) {
		TransactionRunner runner = new TransactionRunner(updater.updater(), minter);

		// IMPORTANT: The EVM executes transactions in the EXACT order they appear in the block.
		// Ordering by gasPrice is the Leader's responsibility when constructing the block.
		// Sorting here would risk BFT state divergence if tie-breaking differs across replicas.
		for (var tx : block.getTransactions()) {
			if (!runner.executeTransaction(tx))
				return false;
		}

		if (commit) {
			//Register participants
			knownAddresses.add(minter);
			for (var tx : block.getTransactions()) {
				// Register sender and receiver as known addresses
				if (tx.from() != null) knownAddresses.add(tx.from());
				if (tx.to() != null) knownAddresses.add(tx.to());

				// For contract creation, pre-register the derived contract address
				if (tx.to() == null && tx.from() != null) {
					Address contractAddr = Address.contractAddress(tx.from(), 0);
					knownAddresses.add(contractAddr);
				}
			}
			runner.getUpdater().commit();
		}

		return true;
	}

	public boolean executeTransaction(Transaction tx, Address minter, boolean commit) {
		TransactionRunner runner = new TransactionRunner(updater.updater(), minter);

		if (!runner.executeTransaction(tx))
			return false;


		if (commit) {
			// Register all addresses involved
			knownAddresses.add(minter);
			if (tx.from() != null) knownAddresses.add(tx.from());
			if (tx.to() != null) knownAddresses.add(tx.to());
			if (tx.to() == null && tx.from() != null) {
				knownAddresses.add(Address.contractAddress(tx.from(), tx.nonce()));
			}

			runner.getUpdater().commit();
		}

		return true;
	}

	/**
	 * Returns a snapshot of the current World State as a TreeMap (sorted by address).
	 * This is used to populate the {@code state} field of a {@link Block} before persistence.
	 *
	 * For EOAs: captures balance and nonce.
	 * For Contract Accounts: captures balance, nonce, bytecode, and storage slots.
	 *
	 * @return TreeMap of address → AccountState, deterministically ordered
	 */
	public TreeMap<String, AccountState> getWorldState() {
		TreeMap<String, AccountState> stateMap = new TreeMap<>();

		for (Address addr : knownAddresses) {
			Account account = updater.get(addr);
			if (account == null) continue;

			String balance = account.getBalance().toBigInteger().toString();
			long nonce = account.getNonce();
			String code = null;
			TreeMap<String, String> storage = new TreeMap<>();

			// For contract accounts, capture bytecode and storage
			if (account.hasCode()) {
				Bytes codeBytes = account.getCode();
				code = codeBytes.toHexString();

				// Capture storage slots
				// Storage entries are accessed via iteration over stored values
				for (var storageEntry : account.getStorageEntries()) {
					String slotKey = storageEntry.getKey().toHexString();
					String slotValue = storageEntry.getValue() != null
						? storageEntry.getValue().toHexString()
						: "0x0";
					storage.put(slotKey, slotValue);
				}
			}

			stateMap.put(addr.toHexString(), new AccountState(balance, nonce, code, storage));
		}

		return stateMap;
	}

	/**
	 * Restores the EVM world state from a persisted state snapshot.
	 * Used during crash recovery to inject the last known state directly,
	 * avoiding the need to replay all transactions.
	 *
	 * For EOAs: restores balance and nonce.
	 * For Contract Accounts: also restores bytecode and storage slots.
	 *
	 * @param stateMap TreeMap of address → AccountState from the last persisted block
	 */
	public void setWorldState(TreeMap<String, AccountState> stateMap) {
		// Clear existing state
		knownAddresses.clear();

		// Recreate accounts from the state snapshot
		for (var entry : stateMap.entrySet()) {
			Address addr = Address.fromHexString(entry.getKey());
			AccountState acctState = entry.getValue();

			MutableAccount account = updater.createAccount(addr);
			account.setBalance(Wei.of(new java.math.BigInteger(acctState.getBalance())));
			account.setNonce(acctState.getNonce());

			// Restore contract bytecode and storage if present
			if (acctState.getCode() != null && !acctState.getCode().isEmpty()) {
				Bytes codeBytes = Bytes.fromHexString(acctState.getCode());
				account.setCode(codeBytes);

				// Restore storage slots
				if (acctState.getStorage() != null) {
					for (var storageEntry : acctState.getStorage().entrySet()) {
						org.hyperledger.besu.datatypes.Hash slotKey =
							org.hyperledger.besu.datatypes.Hash.fromHexString(storageEntry.getKey());
						Bytes slotValue = Bytes.fromHexString(storageEntry.getValue());
						account.setStorageValue(slotKey, slotValue);
					}
				}
			}

			knownAddresses.add(addr);
		}
		updater.commit();
	}

	/** Returns the set of all known addresses (read-only view). */
	public Set<Address> getKnownAddresses() {
		return java.util.Collections.unmodifiableSet(knownAddresses);
	}

	private static String sha256Hex(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(data);
			StringBuilder sb = new StringBuilder("0x");
			for (byte b : hash) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}
}
