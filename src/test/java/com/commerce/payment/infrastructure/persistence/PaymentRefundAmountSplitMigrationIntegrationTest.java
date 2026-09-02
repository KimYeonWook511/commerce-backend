package com.commerce.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 결제 행의 환불 금액 합계를 둘로 가르는 마이그레이션이 기존 값을 옳게 옮기는지 실제 데이터베이스로
 * 확인한다. 단위 테스트 환경은 인메모리 데이터베이스에 매핑으로 스키마를 만들고 마이그레이션을 끄므로
 * 컬럼 이름이나 채운 값이 어긋나도 거기서는 드러나지 않는다.
 *
 * <p>이 저장소에서 마이그레이션을 코드로 단계까지 지정해 돌리는 자리는 여기 하나뿐이다. 다른 통합
 * 테스트가 함께 쓰는 컨테이너는 첫 컨텍스트에서 최신까지 적용해 백필 전 상태를 만들 수 없고, 그
 * 컨테이너의 계정은 자기 데이터베이스 하나에만 권한이 있어 검증용 스키마를 따로 만들 수도 없다.
 * 그래서 이 확인만 컨테이너를 따로 띄운다.
 */
@Tag("docker")
@Testcontainers
class PaymentRefundAmountSplitMigrationIntegrationTest {

	/** 이번 스크립트 직전까지의 상태. 여기까지 적용한 뒤에 옛 컬럼에 값이 쌓인 행을 심는다 */
	private static final String VERSION_BEFORE_SPLIT = "14";
	private static final String VERSION_SPLIT = "15";

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
		.withDatabaseName("migration_check_db")
		.withUsername("test")
		.withPassword("test");

	@BeforeEach
	void migrateToStateBeforeSplit() throws SQLException {
		dropEverything();
		migrateTo(VERSION_BEFORE_SPLIT);
	}

	@DisplayName("마이그레이션 뒤 결제마다 옛 값이 그대로 남고 자기 결제의 성공한 환불 합만 채워진다")
	@Test
	void migrate_whenPaymentsHaveMixedRefunds_backfillsPerPayment() throws SQLException {
		// Given — 옛 컬럼에 값이 쌓인 결제 둘. 각 결제에 성공한 환불과 성공하지 않은 환불이 섞여 있고
		// 두 결제의 성공 환불 합이 서로 다르다. 하나만 두면 백필이 결제별로 묶지 않고 전체 합계를 넣어도
		// 값이 같아 통과한다.
		long first = insertPayment(1L, "first", 10_000, 5_000);
		insertRefund(first, "first-a", 3_000, "SUCCEEDED");
		insertRefund(first, "first-b", 2_000, "UNKNOWN");

		long second = insertPayment(2L, "second", 10_000, 7_000);
		insertRefund(second, "second-a", 1_000, "SUCCEEDED");
		insertRefund(second, "second-b", 500, "SUCCEEDED");
		insertRefund(second, "second-c", 5_500, "MANUAL_REVIEW");

		// When
		migrateTo(VERSION_SPLIT);

		// Then — 돌려주기로 한 금액은 옛 값 그대로이고, 실제로 돌아간 금액은 자기 결제에서 성공한 것만 센다.
		assertThat(refundAmountsOf(first)).containsExactly(5_000, 3_000);
		assertThat(refundAmountsOf(second)).containsExactly(7_000, 1_500);
	}

	@DisplayName("성공한 환불이 없는 결제는 실제로 돌아간 금액이 0으로 남는다")
	@Test
	void migrate_whenNoSucceededRefund_leavesZero() throws SQLException {
		long payment = insertPayment(3L, "pending-only", 10_000, 4_000);
		insertRefund(payment, "pending-only-a", 4_000, "IN_PROGRESS");

		migrateTo(VERSION_SPLIT);

		assertThat(refundAmountsOf(payment)).containsExactly(4_000, 0);
	}

	private void migrateTo(String version) {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.target(MigrationVersion.fromVersion(version))
			.load()
			.migrate();
	}

	/** 앞 테스트가 남긴 스키마·데이터를 지워, 매번 빈 데이터베이스에서 같은 지점까지 다시 적용한다 */
	private void dropEverything() throws SQLException {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.cleanDisabled(false)
			.load()
			.clean();
	}

	private long insertPayment(long orderId, String suffix, int approvedAmount, int totalRefundedAmount)
		throws SQLException {
		String sql = """
			INSERT INTO tbl_payment
			  (order_id, member_id, payment_key, idempotency_key, amount, approved_amount,
			   total_refunded_amount, pg, status, attempt_seq, reconcile_count,
			   created_at, updated_at, version)
			VALUES (?, ?, ?, ?, ?, ?, ?, 'NAVERPAY', 'SUCCEEDED', 1, 0, NOW(6), NOW(6), 0)
			""";

		try (Connection connection = openConnection();
			 PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			statement.setLong(1, orderId);
			statement.setLong(2, 900L);
			statement.setString(3, "PK-" + suffix);
			statement.setString(4, "IDEM-" + suffix);
			statement.setInt(5, approvedAmount);
			statement.setInt(6, approvedAmount);
			statement.setInt(7, totalRefundedAmount);
			statement.executeUpdate();

			try (ResultSet keys = statement.getGeneratedKeys()) {
				keys.next();
				return keys.getLong(1);
			}
		}
	}

	private void insertRefund(long paymentId, String suffix, int amount, String status) throws SQLException {
		String sql = """
			INSERT INTO tbl_refund
			  (payment_id, refund_key, idempotency_key, requester, amount, reason,
			   pg_idempotency_key, status, attempt_seq, reconcile_count,
			   created_at, updated_at, version)
			VALUES (?, ?, ?, 'MEMBER', ?, 'ORDER_CANCELED', ?, ?, 1, 0, NOW(6), NOW(6), 0)
			""";

		try (Connection connection = openConnection();
			 PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, paymentId);
			statement.setString(2, "RF-" + suffix);
			statement.setString(3, "IDEM-" + suffix);
			statement.setInt(4, amount);
			statement.setString(5, "RF-" + suffix + "-1");
			statement.setString(6, status);
			statement.executeUpdate();
		}
	}

	/** 돌려주기로 한 금액과 실제로 돌아간 금액을 그 순서로 돌려준다 */
	private int[] refundAmountsOf(long paymentId) throws SQLException {
		String sql = "SELECT refund_opened_amount, refund_succeeded_amount FROM tbl_payment WHERE id = ?";

		try (Connection connection = openConnection();
			 PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, paymentId);

			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return new int[] {rows.getInt(1), rows.getInt(2)};
			}
		}
	}

	private Connection openConnection() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}
}
