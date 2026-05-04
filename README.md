# DepChain: Enterprise-Grade Permissioned Blockchain

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=java&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.8%2B-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)
![Solidity](https://img.shields.io/badge/Solidity-e6e6e6?style=for-the-badge&logo=solidity&logoColor=black)
![Blockchain](https://img.shields.io/badge/Distributed_Systems-Consensus-blue?style=for-the-badge)

DepChain is a highly dependable, permissioned blockchain implemented from scratch in Java. Built to guarantee resilience and security, it uses the **Basic HotStuff BFT (Byzantine Fault Tolerance)** consensus algorithm to achieve strict linearizability and fault tolerance. 

Beyond core consensus, DepChain integrates the **Hyperledger Besu EVM** to support complex smart contract execution and features a custom-built, highly secure network stack. It natively supports asset transfers, gas mechanics, and includes a custom ERC-20 token implementation fortified against frontrunning attacks.

## Key Features

*   **HotStuff BFT Consensus:** Implements the Basic HotStuff algorithm, utilizing threshold signatures (BLS) and Quorum Certificates (QCs). Robust against malicious (Byzantine) block proposers and replicas, tolerating `f = ⌊(n−1)/3⌋` faults.
*   **Full EVM Integration:** Executes standard Solidity smart contracts exactly like Ethereum by natively wrapping the Hyperledger Besu EVM.
*   **Frontrunning Defense:** Includes an upgraded ERC-20 standard implementation (IST Coin) that neutralizes the notorious "Approval Frontrunning" attack at the smart contract level.
*   **Custom Network Abstractions:** Operates over pure UDP. Implements layered communication building up to Authenticated Perfect Links via ephemeral X25519 Diffie-Hellman handshakes (signed with Ed25519) and HMAC session keys.
*   **Gas & Mempool Management:** Implements an Ethereum-style Gas fee market where transactions are prioritized in the mempool based on fee limits and gas prices.
*   **End-to-End Security:** Strict validation measures preventing spoofing, packet tampering, unauthorized state modifications, double-spending, and replay attacks.

## Architecture

The system is modularized into three main components:
*   `depchain-server`: The core blockchain replica node. Handles BFT consensus, EVM transaction execution logic, block persistence (JSON world state), mempool ordering, and crash recovery.
*   `depchain-client`: A lightweight client library for secure interactions. Signs transactions locally and collects required `f + 1` execution confirmations for state finality.
*   `depchain-common`: Shared infrastructure including the custom UDP protocol stack, cryptographic utilities (DH, BLS, Ed25519), message routing, and transaction serialization.

## Technology Stack

*   **Language:** Java 17+
*   **Virtual Machine:** Hyperledger Besu (EVM)
*   **Smart Contracts:** Solidity
*   **Cryptography:** BouncyCastle (Ed25519, X25519), JPBC (Java Pairing-Based Cryptography for BLS Threshold Signatures)
*   **Build System:** Maven 3.8+
*   **Testing:** JUnit 5 (with extensive Byzantine fault injection and networking simulators)

---

## Getting Started

### Prerequisites
* Java 17 or higher
* Maven 3.8 or higher

### Build
Compile the project and run the complete test suite:
```bash
mvn clean install
```
*(To skip tests during compilation, append `-DskipTests`)*

### Configuration & Key Generation
Before running replicas, cryptographic keys and threshold parameters must be established for the static membership:

1. **Ed25519 keys & Membership config (e.g., 4 members, 1 client):**
   ```bash
   mvn exec:java -pl depchain-common \
     -Dexec.mainClass="tecnico.depchain.depchain_common.KeyGenUtil" \
     -Dexec.args="4 1 config.properties"
   ```

2. **BLS Threshold Parameters (Shared by all replicas):**
   ```bash
   mvn exec:java -pl depchain-server \
     -Dexec.mainClass="tecnico.depchain.depchain_server.hotstuff.ThresholdParamsDealer" \
     -Dexec.args="4 threshold-params.dat"
   ```

## Testing & Fault Injection

The project is heavily tested (45+ JUnit 5 tests) to guarantee correctness under adversarial conditions. The test suite includes custom network filtering to simulate Byzantine behavior such as message dropping, tampering, and key-spoofing.

Run the full suite using `mvn test`, or target specific layers:

**Consensus & Networking Layer:**
```bash
mvn test -pl depchain-server "-Dtest=HotStuffStep5Test"   # Full 4-replica BFT consensus over UDP
mvn test -pl depchain-server "-Dtest=HotStuffStep6Test"   # Crash tolerance & silent Byzantine limits
mvn test -pl depchain-server "-Dtest=CryptoServiceTest"   # BLS Threshold & Ed25519 integration
```

**Transaction Execution & EVM Layer:**
```bash
mvn test -pl depchain-server "-Dtest=MempoolTest"                # Gas-price/nonce ordering
mvn test -pl depchain-server "-Dtest=ISTCoinFrontrunningTest"    # ERC-20 Frontrunning mitigation
mvn test -pl depchain-server "-Dtest=ByzantineClientTest"        # Double-spending & Spoofing defense
```



