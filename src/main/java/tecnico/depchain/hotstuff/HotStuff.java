package tecnico.depchain.hotstuff;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.crypto.SecretKey;

import tecnico.depchain.broadcasts.BestEffortBroadcast;
import tecnico.depchain.hotstuff.Message.MsgType;

public class HotStuff {
	private final int replicaID;
	private final int numReplicas;
	private final int quorumSize; // n - f
	private final BestEffortBroadcast broadcast;

	// Protocol state (Algorithm 2)
	private volatile int currentView = 1;
	private QuorumCertificate prepareQC = null;
	private QuorumCertificate lockedQC = null;

	// Tree of proposals
	private final Map<String, TreeNode> nodeStore = new HashMap<>();

	// Decided commands (the "blockchain")
	private final List<String> decidedCommands = new ArrayList<>();

	// Pending commands to propose (set by upper layer)
	private final BlockingQueue<String> pendingCommands = new LinkedBlockingQueue<>();

	// Message queue for sequential processing
	private final BlockingQueue<Message> messageQueue = new LinkedBlockingQueue<>();
	private final List<Message> outOfOrderBuffer = new ArrayList<>();

	// Callback for decided commands
	private Consumer<String> onDecide = null;

	// Protocol thread
	private Thread protocolThread;
	private volatile boolean running = false;

	// --- View timeout / Pacemaker (Step 4) ---
	private static final long DEFAULT_TIMEOUT_MS = 5000;
	private static final long MAX_TIMEOUT_MS = 60000;
	private long baseTimeoutMs = DEFAULT_TIMEOUT_MS;
	private long viewTimeoutMs = DEFAULT_TIMEOUT_MS;
	private long viewDeadline;

	/**
	 * @param replicaID   This replica's ID (0-indexed)
	 * @param basePort    Base port for the system
	 * @param host        Host address for all replicas
	 * @param numReplicas Total number of replicas
	 * @param keys        List of n shared keys (index i = shared key with replica i)
	 */
	public HotStuff(
			int replicaID, String host, int basePort, int numReplicas, List<SecretKey> keys)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		this.replicaID = replicaID;
		this.numReplicas = numReplicas;
		int f = (numReplicas - 1) / 3;
		this.quorumSize = numReplicas - f;

		List<InetSocketAddress> locals = new ArrayList<>();
		List<InetSocketAddress> remotes = new ArrayList<>();
		List<SecretKey> peerKeys = new ArrayList<>();

		for (int j = 0; j < numReplicas; j++) {
			if (j == replicaID)
				continue;

			int localPort = basePort + replicaID * numReplicas + (j < replicaID ? j : j - 1);
			locals.add(new InetSocketAddress(host, localPort));

			int remotePort = basePort + j * numReplicas + (replicaID < j ? replicaID : replicaID - 1);
			remotes.add(new InetSocketAddress(host, remotePort));

			peerKeys.add(keys.get(j));
		}

		SecretKey ownKey = keys.get(replicaID);
		broadcast = new BestEffortBroadcast(this::handleMsg, this::handleMsg, locals, ownKey, remotes, peerKeys);
	}

	// --- Public interface ---

	public void start() {
		running = true;
		protocolThread = new Thread(this::protocolLoop, "HotStuff-" + replicaID);
		protocolThread.setDaemon(true);
		protocolThread.start();
	}

	public void stop() {
		running = false;
		if (protocolThread != null) {
			protocolThread.interrupt();
			try { protocolThread.join(3000); } catch (InterruptedException ignored) {}
		}
		broadcast.close();
	}

	public void propose(String command) {
		this.pendingCommands.offer(command);
	}

	public List<String> getDecidedCommands() {
		return new ArrayList<>(decidedCommands);
	}

	public void setOnDecide(Consumer<String> callback) {
		this.onDecide = callback;
	}

	public int getCurrentView() {
		return currentView;
	}

	public void setBaseTimeout(long timeoutMs) {
		this.baseTimeoutMs = timeoutMs;
		this.viewTimeoutMs = timeoutMs;
	}

	// --- Network layer ---

	private void handleMsg(byte[] data, InetSocketAddress remote) {
		byte[] msgBytes = new byte[data.length - 1];
		System.arraycopy(data, 1, msgBytes, 0, msgBytes.length);

		Message msg = Message.deserialize(msgBytes);
		if (msg != null) {
			messageQueue.offer(msg);
		}
	}

	private void sendMessage(int replica, Message msg) {
		if (replica == replicaID) {
			messageQueue.offer(msg);
			return;
		}
		int link = replica > replicaID ? replica - 1 : replica;
		broadcast.transmit(link, msg.serialize());
	}

	private void broadcastMessage(Message msg) {
		broadcast.broadcast(msg.serialize());
	}

	// --- Timeout helpers ---

	private void startViewTimer() {
		viewDeadline = System.currentTimeMillis() + viewTimeoutMs;
	}

	private long remainingMs() {
		return Math.max(0, viewDeadline - System.currentTimeMillis());
	}

	// --- Protocol helpers ---

	/**
	 * Pull a message of the given type and view from the queue.
	 * Returns null if the view deadline expires before a matching message arrives.
	 */
	private Message pullMessage(MsgType type, int viewNumber) throws InterruptedException {
		for (int i = 0; i < outOfOrderBuffer.size(); i++) {
			Message msg = outOfOrderBuffer.get(i);
			if (msg.getType() == type && msg.getViewNumber() == viewNumber) {
				return outOfOrderBuffer.remove(i);
			}
		}
		while (true) {
			long remaining = remainingMs();
			if (remaining <= 0)
				return null;

			Message msg = messageQueue.poll(remaining, TimeUnit.MILLISECONDS);
			if (msg == null)
				return null;

			if (msg.getType() == type && msg.getViewNumber() == viewNumber) {
				return msg;
			}
			if (msg.getViewNumber() >= currentView - 1) {
				outOfOrderBuffer.add(msg);
			}
		}
	}

	private int getLeader(int view) {
		return view % numReplicas;
	}

	private boolean isLeader() {
		return getLeader(currentView) == replicaID;
	}

	private Message makeMsg(MsgType type, TreeNode node, QuorumCertificate qc) {
		return new Message(type, currentView, replicaID, node, qc);
	}

	/**
	 * safeNode predicate (Algorithm 1, lines 25-27).
	 */
	private boolean safeNode(TreeNode node, QuorumCertificate qc) {
		if (lockedQC == null)
			return true;

		boolean extendsLocked = node.extendsFrom(lockedQC.getNode());
		boolean higherView = qc != null && qc.getViewNumber() > lockedQC.getViewNumber();

		return extendsLocked || higherView;
	}

	private TreeNode createLeaf(TreeNode parent, String command) {
		TreeNode leaf = new TreeNode(parent, command);
		storeNode(leaf);
		return leaf;
	}

	private void storeNode(TreeNode node) {
		nodeStore.put(hashHex(node.getHash()), node);
	}

	private void linkParent(TreeNode node) {
		if (node == null)
			return;
		TreeNode parent = nodeStore.get(hashHex(node.getParentHash()));
		if (parent != null) {
			node.setParent(parent);
		}
	}

	private static String hashHex(byte[] hash) {
		StringBuilder sb = new StringBuilder();
		for (byte b : hash)
			sb.append(String.format("%02x", b));
		return sb.toString();
	}

	// --- Protocol main loop (with timeout / nextView interrupt) ---

	private void protocolLoop() {
		while (running) {
			startViewTimer();
			boolean viewSucceeded = false;

			try {
				if (isLeader()) {
					viewSucceeded = runLeaderPhase();
				} else {
					viewSucceeded = runReplicaPhase();
				}
			} catch (InterruptedException e) {
				break;
			} finally {
				if (running) {
					sendNewViewToNextLeader();

					if (viewSucceeded) {
						viewTimeoutMs = baseTimeoutMs;
					} else {
						viewTimeoutMs = Math.min(viewTimeoutMs * 2, MAX_TIMEOUT_MS);
					}
					currentView++;
				}
			}
		}
	}

	/**
	 * Send NEW_VIEW carrying our highest prepareQC to the next view's leader.
	 * Called on both successful completion and timeout (Algorithm 2, line 36).
	 */
	private void sendNewViewToNextLeader() {
		int nextLeader = getLeader(currentView + 1);
		sendMessage(nextLeader, makeMsg(MsgType.NEW_VIEW, null, prepareQC));
	}

	/** @return true if the view completed successfully, false on timeout */
	private boolean runLeaderPhase() throws InterruptedException {
		// === PREPARE phase (leader) ===
		QuorumCertificate highQC = null;

		if (currentView == 1) {
			// Bootstrap: no NEW_VIEW messages needed
		} else {
			Map<Integer, Message> newViews = new HashMap<>();

			while (newViews.size() < quorumSize) {
				Message msg = pullMessage(MsgType.NEW_VIEW, currentView - 1);
				if (msg == null) return false;
				newViews.put(msg.getSenderId(), msg);
			}

			for (Message m : newViews.values()) {
				QuorumCertificate qc = m.getJustify();
				if (qc != null) {
					if (highQC == null || qc.getViewNumber() > highQC.getViewNumber())
						highQC = qc;
				}
			}
		}

		// Create new proposal
		TreeNode parentNode = highQC != null ? highQC.getNode() : null;
		if (parentNode != null)
			linkParent(parentNode);

		String cmd = waitForCommand();
		if (cmd == null) return false;

		TreeNode proposal = createLeaf(parentNode, cmd);
		broadcastMessage(makeMsg(MsgType.PREPARE, proposal, highQC));

		boolean selfVote = safeNode(proposal, highQC);

		// === Collect PREPARE votes -> form prepareQC ===
		QuorumCertificate newPrepareQC = new QuorumCertificate(MsgType.PREPARE, currentView, proposal);
		if (selfVote)
			newPrepareQC.addVoter(replicaID);

		while (!newPrepareQC.hasQuorum(quorumSize)) {
			Message msg = pullMessage(MsgType.PREPARE, currentView);
			if (msg == null) return false;
			newPrepareQC.addVoter(msg.getSenderId());
		}

		// === PRE-COMMIT phase ===
		prepareQC = newPrepareQC;
		broadcastMessage(makeMsg(MsgType.PRE_COMMIT, null, prepareQC));

		QuorumCertificate newPrecommitQC = new QuorumCertificate(MsgType.PRE_COMMIT, currentView, proposal);
		newPrecommitQC.addVoter(replicaID);

		while (!newPrecommitQC.hasQuorum(quorumSize)) {
			Message msg = pullMessage(MsgType.PRE_COMMIT, currentView);
			if (msg == null) return false;
			newPrecommitQC.addVoter(msg.getSenderId());
		}

		// === COMMIT phase ===
		lockedQC = newPrecommitQC;
		broadcastMessage(makeMsg(MsgType.COMMIT, null, newPrecommitQC));

		QuorumCertificate newCommitQC = new QuorumCertificate(MsgType.COMMIT, currentView, proposal);
		newCommitQC.addVoter(replicaID);

		while (!newCommitQC.hasQuorum(quorumSize)) {
			Message msg = pullMessage(MsgType.COMMIT, currentView);
			if (msg == null) return false;
			newCommitQC.addVoter(msg.getSenderId());
		}

		// === DECIDE phase ===
		broadcastMessage(makeMsg(MsgType.DECIDE, null, newCommitQC));
		executeDecision(proposal);
		return true;
	}

	/** @return true if the view completed successfully, false on timeout */
	private boolean runReplicaPhase() throws InterruptedException {
		int leader = getLeader(currentView);

		// === PREPARE phase (replica) ===
		Message prepareMsg = pullMessage(MsgType.PREPARE, currentView);
		if (prepareMsg == null) return false;

		TreeNode proposal = prepareMsg.getTreeNode();

		if (proposal != null) {
			storeNode(proposal);
			linkParent(proposal);
		}

		QuorumCertificate justifyQC = prepareMsg.getJustify();

		if (justifyQC != null && justifyQC.getNode() != null) {
			storeNode(justifyQC.getNode());
			linkParent(justifyQC.getNode());
			if (proposal != null) {
				if (Arrays.equals(proposal.getParentHash(), justifyQC.getNode().getHash())) {
					proposal.setParent(justifyQC.getNode());
				}
			}
		}

		if (proposal != null && safeNode(proposal, justifyQC)) {
			sendMessage(leader, makeMsg(MsgType.PREPARE, proposal, null));
		}

		// === PRE-COMMIT phase (replica) ===
		Message preCommitMsg = pullMessage(MsgType.PRE_COMMIT, currentView);
		if (preCommitMsg == null) return false;

		QuorumCertificate rcvPrepareQC = preCommitMsg.getJustify();
		if (rcvPrepareQC != null) {
			prepareQC = rcvPrepareQC;
		}
		sendMessage(leader, makeMsg(MsgType.PRE_COMMIT, proposal, null));

		// === COMMIT phase (replica) ===
		Message commitMsg = pullMessage(MsgType.COMMIT, currentView);
		if (commitMsg == null) return false;

		QuorumCertificate rcvPrecommitQC = commitMsg.getJustify();
		if (rcvPrecommitQC != null) {
			lockedQC = rcvPrecommitQC;
		}
		sendMessage(leader, makeMsg(MsgType.COMMIT, proposal, null));

		// === DECIDE phase (replica) ===
		Message decideMsg = pullMessage(MsgType.DECIDE, currentView);
		if (decideMsg == null) return false;

		QuorumCertificate commitQC = decideMsg.getJustify();
		if (commitQC != null && commitQC.getNode() != null) {
			executeDecision(commitQC.getNode());
		} else if (proposal != null) {
			executeDecision(proposal);
		}
		return true;
	}

	/**
	 * Wait for a command to propose, respecting the view deadline.
	 * Returns null if no command arrives before the view expires.
	 */
	private String waitForCommand() throws InterruptedException {
		long remaining = remainingMs();
		if (remaining <= 0)
			return null;

		return pendingCommands.poll(remaining, TimeUnit.MILLISECONDS);
	}

	private void executeDecision(TreeNode node) {
		if (node == null)
			return;
		String cmd = node.getCommand();
		if (cmd != null && !decidedCommands.contains(cmd)) {
			decidedCommands.add(cmd);
			if (onDecide != null) {
				onDecide.accept(cmd);
			}
		}
	}
}
