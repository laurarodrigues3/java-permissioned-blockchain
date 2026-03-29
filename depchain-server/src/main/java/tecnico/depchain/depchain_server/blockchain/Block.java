package tecnico.depchain.depchain_server.blockchain;

import java.io.Serializable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import tecnico.depchain.depchain_common.DepchainUtils;
import tecnico.depchain.depchain_common.blockchain.Transaction;

/**
 * Represents a finalized block in the DepChain blockchain.
 *
 * The {@code state} field uses a {@link TreeMap} to guarantee deterministic
 * key ordering across all replicas, which is critical for BFT consensus:
 * all nodes must compute the exact same {@code blockHash} for the same block.
 */
public class Block implements Serializable {

    private String blockHash;
    private String previousBlockHash;
    private List<Transaction> transactions;
    private TreeMap<String, AccountState> state; //TODO: Nuke and replace with WorldState, implement JSON dumping for it
    // Will require address, balance, nonce, code, storage and any other state you may remember

    /** Default constructor for Gson deserialization. */
    public Block() {
        this.transactions = new ArrayList<>();
        this.state = new TreeMap<>();
    }

    /**
     * Constructs a new Block with all fields, and automatically calculates the hash.
     *
     * @param previousBlockHash Hash of the previous block (null for genesis)
     * @param transactions      List of transactions executed in this block
     * @param state             World State snapshot after executing all transactions
     */
    public Block(String previousBlockHash, List<Transaction> transactions, TreeMap<String, AccountState> state) {
        this.previousBlockHash = previousBlockHash;
        this.transactions = transactions != null ? transactions : new ArrayList<>();
        this.state = state != null ? state : new TreeMap<>();
        this.blockHash = calculateHash();
    }

    // ── Getters & Setters ───────────────────────────────────────────────

    public String getBlockHash() { return blockHash; }
    public void setBlockHash(String blockHash) { this.blockHash = blockHash; }

    public String getPreviousBlockHash() { return previousBlockHash; }
    public void setPreviousBlockHash(String previousBlockHash) { this.previousBlockHash = previousBlockHash; }

    public List<Transaction> getTransactions() { return transactions; }
    public void setTransactions(List<Transaction> transactions) { this.transactions = transactions; }

    public TreeMap<String, AccountState> getState() { return state; }
    public void setState(TreeMap<String, AccountState> state) { this.state = state; }

    // ── Deterministic Hashing ───────────────────────────────────────────

    /**
     * Computes a deterministic SHA-256 hash of this block.
     *
     * The hash is derived from: previousBlockHash + canonical transaction data + sorted state.
     * Because {@code state} is a {@link TreeMap}, iteration order is always alphabetical
     * by address, ensuring identical hashes across all BFT replicas.
     *
     * @return Hex-encoded SHA-256 hash string
     */
    public String calculateHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // 1. Previous block hash
            String prev = previousBlockHash != null ? previousBlockHash : "null";
            digest.update(prev.getBytes(StandardCharsets.UTF_8));

            // 2. Transactions (deterministic: the list order is the execution order)
            for (Transaction tx : transactions) {
                digest.update(tx.canonicalBytes());
            }

            // 3. World State (TreeMap guarantees sorted iteration)
            for (var entry : state.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                AccountState as = entry.getValue();
                digest.update(as.getBalance().getBytes(StandardCharsets.UTF_8));
                digest.update(Long.toString(as.getNonce()).getBytes(StandardCharsets.UTF_8));
                if (as.getCodeHash() != null) {
                    digest.update(as.getCodeHash().getBytes(StandardCharsets.UTF_8));
                }
            }

            byte[] hashBytes = digest.digest();
            return DepchainUtils.toHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
