package com.commerce.test.support;

public interface PersistenceTestSupport {

	CleanupOrder cleanupOrder();

	void deleteAllInBatch();

}
