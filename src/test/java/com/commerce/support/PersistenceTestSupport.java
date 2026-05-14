package com.commerce.support;

public interface PersistenceTestSupport {

	CleanupOrder cleanupOrder();

	void deleteAllInBatch();

}
