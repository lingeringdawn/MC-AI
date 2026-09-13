package dev.mcpfabric.client.tasks;

/** Terminal / transient states a {@link ClientTask} can be in. */
public enum ClientTaskStatus {
	/** Still ticking on the game thread. */
	RUNNING,
	/** Finished successfully. */
	DONE,
	/** Finished but could not achieve the goal (unreachable, timeout, ...). */
	FAILED,
	/** Aborted by an explicit cancel. */
	CANCELLED
}
