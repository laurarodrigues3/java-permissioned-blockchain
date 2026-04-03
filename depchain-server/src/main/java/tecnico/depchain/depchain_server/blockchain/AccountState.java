package tecnico.depchain.depchain_server.blockchain;

import java.io.Serializable;
import java.util.TreeMap;

/**
 * DTO representing the state of a single account in the World State snapshot
 * persisted within each Block.
 *
 * For EOAs: balance + nonce are sufficient.
 * For Contract Accounts: balance + nonce + code (bytecode) + storage (key-value pairs)
 * are required to fully restore the contract state.
 */
public class AccountState implements Serializable {

    private String balance;      // Decimal string (Wei)
    private long nonce;
    private String code;          // Hex string of bytecode; null for EOAs
    private TreeMap<String, String> storage;  // Storage slots: slot -> value (both hex strings)

    public AccountState() {
        this.storage = new TreeMap<>();
    }

    public AccountState(String balance, long nonce, String code, TreeMap<String, String> storage) {
        this.balance = balance;
        this.nonce = nonce;
        this.code = code;
        this.storage = storage != null ? storage : new TreeMap<>();
    }

    // Legacy constructor for EOA-only states (backwards compatible)
    public AccountState(String balance, long nonce, String codeHash) {
        this.balance = balance;
        this.nonce = nonce;
        this.code = null;  // Legacy: codeHash not used for recovery
        this.storage = new TreeMap<>();
    }

    public String getBalance() { return balance; }
    public void setBalance(String balance) { this.balance = balance; }

    public long getNonce() { return nonce; }
    public void setNonce(long nonce) { this.nonce = nonce; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public TreeMap<String, String> getStorage() { return storage; }
    public void setStorage(TreeMap<String, String> storage) { this.storage = storage; }

    // Legacy method - returns codeHash for backwards compatibility
    public String getCodeHash() { return code != null ? code.substring(0, Math.min(16, code.length())) : null; }
    public void setCodeHash(String codeHash) { /* no-op for legacy compatibility */ }

    public boolean isContract() { return code != null && !code.isEmpty(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccountState that = (AccountState) o;
        return nonce == that.nonce
                && java.util.Objects.equals(balance, that.balance)
                && java.util.Objects.equals(code, that.code)
                && java.util.Objects.equals(storage, that.storage);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(balance, nonce, code, storage);
    }

    @Override
    public String toString() {
        return "AccountState{balance='" + balance + "', nonce=" + nonce +
                ", code=" + (code != null ? code.length() + " bytes" : "null") +
                ", storage=" + storage.size() + " slots}";
    }
}