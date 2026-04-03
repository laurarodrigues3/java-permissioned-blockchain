# DepChain

Highly Dependable Systems — 2025-2026

DepChain is a permissioned blockchain built around the Basic HotStuff BFT consensus algorithm. It supports native DepCoin transfers, smart contract execution on the Hyperledger Besu EVM, and includes an IST Coin ERC-20 token with frontrunning-resistant `approve()`.

## Modules

- `depchain-server` — Blockchain replica: consensus, EVM execution, block persistence, mempool, crash recovery.
- `depchain-client` — Client library: signs/submits transactions, collects f+1 confirmations.
- `depchain-common` — Shared code: UDP link stack (FairLoss → Stubborn → APL with X25519 DH), broadcasts, message types, `Transaction`/`SignedTransaction`, membership.

Networking uses UDP with layered link abstractions. Links are authenticated via an ephemeral X25519 DH handshake (signed with Ed25519) that derives per-session HMAC keys. Quorum Certificates use BLS threshold signatures (JPBC library). The system tolerates f = ⌊(n−1)/3⌋ Byzantine replicas.

## Build

Requires Java 17+ and Maven 3.8+.

```bash
mvn clean install              # compile + run tests
mvn clean install -DskipTests  # compile only
```

### Configuration

Generate keys and threshold params before running:

```bash
# Ed25519 keys + membership config (4 members, 1 client)
mvn exec:java -pl depchain-common \
  -Dexec.mainClass="tecnico.depchain.depchain_common.KeyGenUtil" \
  -Dexec.args="4 1 config.properties"

# BLS threshold params (run once, shared by all replicas)
mvn exec:java -pl depchain-server \
  -Dexec.mainClass="tecnico.depchain.depchain_server.hotstuff.ThresholdParamsDealer" \
  -Dexec.args="4 threshold-params.dat"
```

This produces:
- `config.properties` — Ed25519 keys, network addresses, account addresses.
- `threshold-params.dat` — BLS pairing params, generator, public key, per-replica shares.

## Tests

The test suite (45 JUnit 5 tests) covers correctness, security, and fault tolerance. Run everything with:

```bash
mvn test
```

Or run individual test classes:

**Stage 1 (Consensus):**

```bash
mvn test -pl depchain-server "-Dtest=HotStuffStep3Test"   # link stack (stubborn, APL, wrong-key rejection)
mvn test -pl depchain-server "-Dtest=HotStuffStep4Test"   # TreeNode, Message, QC data structures
mvn test -pl depchain-server "-Dtest=CryptoServiceTest"   # Ed25519, BLS threshold, QC integration
mvn test -pl depchain-server "-Dtest=HotStuffStep5Test"   # full 4-replica consensus over UDP
mvn test -pl depchain-server "-Dtest=HotStuffStep6Test"   # crash tolerance, silent Byzantine
```

**Stage 2 (Transactions):**

```bash
mvn test -pl depchain-server "-Dtest=GenesisAndEVMTest"          # genesis loading, world state snapshot/restore
mvn test -pl depchain-server "-Dtest=TransactionRunnerTest"      # transfers, gas fees, nonce checks
mvn test -pl depchain-server "-Dtest=MempoolTest"                # ordering by gas price, nonce, gas cap
mvn test -pl depchain-server "-Dtest=BlockPersistenceTest"       # block save/load round-trip
mvn test -pl depchain-server "-Dtest=ISTCoinFrontrunningTest"    # ERC-20 + frontrunning protection
mvn test -pl depchain-server "-Dtest=ByzantineClientTest"        # spoofing, overspend, replay, double-spend
```

## Fault Injection

Tests simulate Byzantine behaviour in two ways:

1. **Message filter** — drops or modifies outgoing messages:
```java
replicas.get(byzantineId).setOutgoingFilter((dest, msg) -> {
    return null; // silent Byzantine: drop everything
});
```

2. **Wrong keys** — replicas created with mismatched Ed25519 keys to test authentication rejection.
