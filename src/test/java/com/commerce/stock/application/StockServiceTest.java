package com.commerce.stock.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.exception.ProductErrorCode;
import com.commerce.product.exception.ProductException;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.domain.StockAdjustmentReason;
import com.commerce.stock.domain.StockHistory;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.repository.StockHistoryRepository;
import com.commerce.stock.domain.repository.StockRepository;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;
import com.commerce.stock.application.command.AdminStockAdjustCommand;
import com.commerce.stock.application.command.AdminStockCreateCommand;
import com.commerce.stock.application.command.StockDecreaseBatchCommand;
import com.commerce.stock.application.result.AdminStockResult;
import com.commerce.stock.application.result.StockDecreaseBatchResult;
import com.commerce.stock.application.result.StockHistoryResult;

@ExtendWith(MockitoExtension.class)
class StockServicesTest {

	@Mock
	private StockRepository stockRepository;

	@Mock
	private StockHistoryRepository stockHistoryRepository;

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private AdminStockService adminStockService;

	@InjectMocks
	private OrderStockService orderStockService;

	@InjectMocks
	private StockConcurrencyService stockConcurrencyService;

	@DisplayName("관리자 초기 재고 생성은 재고와 양수 이력을 저장한다")
	@Test
	void createInitialStock_whenValid_saveStockAndHistory() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		AdminStockCreateCommand command = AdminStockCreateCommand.builder()
			.productId(1L)
			.quantity(10)
			.reason(StockAdjustmentReason.INBOUND)
			.adminMemberId(10L)
			.build();

		given(productRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(product));
		given(stockRepository.findByProductId(1L)).willReturn(Optional.empty());
		given(stockRepository.save(any(Stock.class))).willAnswer(invocation -> {
			Stock stock = invocation.getArgument(0);
			ReflectionTestUtils.setField(stock, "id", 100L);
			return stock;
		});

		// when
		AdminStockResult result = adminStockService.createInitialStock(command);

		// then
		assertThat(result.getProductId()).isEqualTo(1L);
		assertThat(result.getStockId()).isEqualTo(100L);
		assertThat(result.getQuantity()).isEqualTo(10);

		ArgumentCaptor<StockHistory> historyCaptor = ArgumentCaptor.forClass(StockHistory.class);
		then(stockHistoryRepository).should().save(historyCaptor.capture());
		StockHistory history = historyCaptor.getValue();
		assertThat(history.getStock().getId()).isEqualTo(100L);
		assertThat(history.getQuantityChange()).isEqualTo(10);
		assertThat(history.getReason()).isEqualTo(StockAdjustmentReason.INBOUND);
		assertThat(history.getAdminMemberId()).isEqualTo(10L);
	}

	@DisplayName("초기 재고 생성 대상 상품이 없으면 예외가 발생한다")
	@Test
	void createInitialStock_whenProductNotFound_throwException() {
		// given
		AdminStockCreateCommand command = AdminStockCreateCommand.builder()
			.productId(1L)
			.quantity(10)
			.reason(StockAdjustmentReason.INBOUND)
			.adminMemberId(10L)
			.build();

		given(productRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> adminStockService.createInitialStock(command))
			.isInstanceOf(ProductException.class)
			.satisfies(exception -> {
				ProductException productException = (ProductException) exception;
				assertThat(productException.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
			});
	}

	@DisplayName("관리자 초기 재고 수량이 0이면 0 수량 이력을 함께 저장한다")
	@Test
	void createInitialStock_whenQuantityIsZero_saveStockAndZeroHistory() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		AdminStockCreateCommand command = AdminStockCreateCommand.builder()
			.productId(1L)
			.quantity(0)
			.reason(StockAdjustmentReason.INBOUND)
			.adminMemberId(10L)
			.build();

		given(productRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(product));
		given(stockRepository.findByProductId(1L)).willReturn(Optional.empty());
		given(stockRepository.save(any(Stock.class))).willAnswer(invocation -> {
			Stock stock = invocation.getArgument(0);
			ReflectionTestUtils.setField(stock, "id", 100L);
			return stock;
		});

		// when
		AdminStockResult result = adminStockService.createInitialStock(command);

		// then
		assertThat(result.getProductId()).isEqualTo(1L);
		assertThat(result.getStockId()).isEqualTo(100L);
		assertThat(result.getQuantity()).isZero();

		ArgumentCaptor<StockHistory> historyCaptor = ArgumentCaptor.forClass(StockHistory.class);
		then(stockHistoryRepository).should().save(historyCaptor.capture());
		StockHistory history = historyCaptor.getValue();
		assertThat(history.getStock().getId()).isEqualTo(100L);
		assertThat(history.getQuantityChange()).isZero();
		assertThat(history.getReason()).isEqualTo(StockAdjustmentReason.INBOUND);
		assertThat(history.getAdminMemberId()).isEqualTo(10L);
	}

	@DisplayName("이미 재고가 있으면 초기 재고 생성에 실패한다")
	@Test
	void createInitialStock_whenStockAlreadyExists_throwException() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		Stock stock = createStock(product, 10);
		AdminStockCreateCommand command = AdminStockCreateCommand.builder()
			.productId(1L)
			.quantity(10)
			.reason(StockAdjustmentReason.INBOUND)
			.adminMemberId(10L)
			.build();

		given(productRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(product));
		given(stockRepository.findByProductId(1L)).willReturn(Optional.of(stock));

		// when & then
		assertThatThrownBy(() -> adminStockService.createInitialStock(command))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.STOCK_ALREADY_EXISTS);
			});
	}

	@DisplayName("관리자 재고 증가는 비관적 락으로 조회하고 양수 이력을 저장한다")
	@Test
	void increaseByAdmin_whenStockExists_increaseQuantityAndSavePositiveHistory() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		Stock stock = createStock(product, 10);
		ReflectionTestUtils.setField(stock, "id", 100L);
		AdminStockAdjustCommand command = AdminStockAdjustCommand.builder()
			.productId(1L)
			.quantity(5)
			.reason(StockAdjustmentReason.INBOUND)
			.adminMemberId(10L)
			.build();

		given(stockRepository.findByProductIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

		// when
		AdminStockResult result = adminStockService.increaseByAdmin(command);

		// then
		assertThat(stock.getQuantity()).isEqualTo(15);
		assertThat(result.getQuantity()).isEqualTo(15);

		ArgumentCaptor<StockHistory> historyCaptor = ArgumentCaptor.forClass(StockHistory.class);
		then(stockHistoryRepository).should().save(historyCaptor.capture());
		assertThat(historyCaptor.getValue().getQuantityChange()).isEqualTo(5);
		assertThat(historyCaptor.getValue().getReason()).isEqualTo(StockAdjustmentReason.INBOUND);
	}

	@DisplayName("관리자 재고 감소는 비관적 락으로 조회하고 음수 이력을 저장한다")
	@Test
	void decreaseByAdmin_whenStockExists_decreaseQuantityAndSaveNegativeHistory() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		Stock stock = createStock(product, 10);
		ReflectionTestUtils.setField(stock, "id", 100L);
		AdminStockAdjustCommand command = AdminStockAdjustCommand.builder()
			.productId(1L)
			.quantity(3)
			.reason(StockAdjustmentReason.DISPOSAL)
			.adminMemberId(10L)
			.build();

		given(stockRepository.findByProductIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

		// when
		AdminStockResult result = adminStockService.decreaseByAdmin(command);

		// then
		assertThat(stock.getQuantity()).isEqualTo(7);
		assertThat(result.getQuantity()).isEqualTo(7);

		ArgumentCaptor<StockHistory> historyCaptor = ArgumentCaptor.forClass(StockHistory.class);
		then(stockHistoryRepository).should().save(historyCaptor.capture());
		assertThat(historyCaptor.getValue().getQuantityChange()).isEqualTo(-3);
		assertThat(historyCaptor.getValue().getReason()).isEqualTo(StockAdjustmentReason.DISPOSAL);
	}

	@DisplayName("관리자 재고 감소 수량이 현재 재고보다 크면 예외가 발생한다")
	@Test
	void decreaseByAdmin_whenOutOfStock_throwException() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		Stock stock = createStock(product, 1);
		AdminStockAdjustCommand command = AdminStockAdjustCommand.builder()
			.productId(1L)
			.quantity(2)
			.reason(StockAdjustmentReason.DISPOSAL)
			.adminMemberId(10L)
			.build();

		given(stockRepository.findByProductIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

		// when & then
		assertThatThrownBy(() -> adminStockService.decreaseByAdmin(command))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.OUT_OF_STOCK);
			});
		then(stockHistoryRepository).should(never()).save(any(StockHistory.class));
	}

	@DisplayName("상품별 재고 이력은 최신순 결과로 반환된다")
	@Test
	void getHistoriesByProductId_whenStockExists_returnHistoriesOrderByCreatedAtDesc() {
		// given
		Product product = createProduct(1L, "product-1", 1000);
		Stock stock = createStock(product, 10);
		ReflectionTestUtils.setField(stock, "id", 100L);
		StockHistory latestHistory = createStockHistory(2L, stock, -3, StockAdjustmentReason.DISPOSAL,
			LocalDateTime.of(2026, 4, 30, 12, 30));
		StockHistory firstHistory = createStockHistory(1L, stock, 10, StockAdjustmentReason.INBOUND,
			LocalDateTime.of(2026, 4, 30, 12, 0));

		given(stockRepository.findByProductId(1L)).willReturn(Optional.of(stock));
		given(stockHistoryRepository.findAllByStockProductIdOrderByCreatedAtDesc(1L))
			.willReturn(List.of(latestHistory, firstHistory));

		// when
		List<StockHistoryResult> results = adminStockService.getHistoriesByProductId(1L);

		// then
		assertThat(results).hasSize(2);
		assertThat(results.get(0).getHistoryId()).isEqualTo(2L);
		assertThat(results.get(0).getQuantityChange()).isEqualTo(-3);
		assertThat(results.get(0).getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 4, 30, 12, 30));
		assertThat(results.get(1).getHistoryId()).isEqualTo(1L);
		assertThat(results.get(1).getQuantityChange()).isEqualTo(10);
		assertThat(results.get(1).getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 4, 30, 12, 0));
	}

	@DisplayName("재고가 존재하면 차감된다")
	@Test
	void decrease_whenStockExists_decreaseQuantity() {
		// given
		Stock stock = createStock(10);
		given(stockRepository.findByProductId(1L)).willReturn(Optional.of(stock));

		// when
		stockConcurrencyService.decrease(1L, 3);

		// then
		assertThat(stock.getQuantity()).isEqualTo(7);
	}

	@DisplayName("비관적 락으로 조회한 재고는 차감된다")
	@Test
	void decreaseWithPessimisticLock_whenStockExists_decreaseQuantity() {
		// given
		Stock stock = createStock(10);
		given(stockRepository.findByProductIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

		// when
		orderStockService.decreaseWithPessimisticLock(1L, 4);

		// then
		assertThat(stock.getQuantity()).isEqualTo(6);
	}

	@DisplayName("비관적 락으로 조회한 재고는 증가한다")
	@Test
	void increaseWithPessimisticLock_whenStockExists_increaseQuantity() {
		// given
		Stock stock = createStock(5);
		given(stockRepository.findByProductIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

		// when
		orderStockService.increaseWithPessimisticLock(1L, 3);

		// then
		assertThat(stock.getQuantity()).isEqualTo(8);
	}

	@DisplayName("비관적 락으로 여러 재고를 조회하면 모두 차감된다")
	@Test
	void decreaseBatchWithPessimisticLock_whenStocksExist_decreaseQuantities() {
		// given
		Product product1 = createProduct(1L, "product-1", 1000);
		Product product2 = createProduct(2L, "product-2", 1200);
		Stock stock1 = createStock(product1, 10);
		Stock stock2 = createStock(product2, 9);

		StockDecreaseBatchCommand command = StockDecreaseBatchCommand.from(
			Map.of(1L, 1, 2L, 1)
		);

		given(stockRepository.findAllByProductIdInWithPessimisticLock(argThat(ids ->
			ids.containsAll(List.of(1L, 2L)) && ids.size() == 2
		)))
			.willReturn(List.of(stock1, stock2));

		// when
		orderStockService.decreaseBatchWithPessimisticLock(command);

		// then
		assertThat(stock1.getQuantity()).isEqualTo(9);
		assertThat(stock2.getQuantity()).isEqualTo(8);
	}

	@DisplayName("비관적 락 배치 요청은 차감 후 요약 정보를 반환한다")
	@Test
	void decreaseBatchWithPessimisticLock_whenRequest_returnSummary() {
		// given
		Product product1 = createProduct(1L, "product-1", 1000);
		Product product2 = createProduct(2L, "product-2", 1200);
		Stock stock1 = createStock(product1, 10);
		Stock stock2 = createStock(product2, 9);

		StockDecreaseBatchCommand command = StockDecreaseBatchCommand.builder()
			.quantitiesByProductId(Map.of(1L, 2, 2L, 1))
			.build();

		given(stockRepository.findAllByProductIdInWithPessimisticLock(argThat(ids ->
			ids.containsAll(List.of(1L, 2L)) && ids.size() == 2
		)))
			.willReturn(List.of(stock1, stock2));

		// when
		StockDecreaseBatchResult result = orderStockService.decreaseBatchWithPessimisticLock(command);

		// then
		assertThat(stock1.getQuantity()).isEqualTo(8);
		assertThat(stock2.getQuantity()).isEqualTo(8);
		assertThat(result.getItemCount()).isEqualTo(2);
		assertThat(result.getTotalQuantity()).isEqualTo(3);
	}

	@DisplayName("비관적 락 조회 결과가 부족하면 예외가 발생한다")
	@Test
	void decreaseBatchWithPessimisticLock_whenStockMissing_throwException() {
		// given
		Product product1 = createProduct(1L, "product-1", 1000);
		Stock stock1 = createStock(product1, 10);

		StockDecreaseBatchCommand command = StockDecreaseBatchCommand.from(
			Map.of(1L, 1, 2L, 1)
		);

		given(stockRepository.findAllByProductIdInWithPessimisticLock(argThat(ids ->
			ids.containsAll(List.of(1L, 2L)) && ids.size() == 2
		)))
			.willReturn(List.of(stock1));

		// when & then
		assertThatThrownBy(() -> orderStockService.decreaseBatchWithPessimisticLock(command))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
			});
	}

	@DisplayName("재고가 없으면 예외가 발생한다")
	@Test
	void decrease_whenStockNotFound_throwException() {
		// given
		given(stockRepository.findByProductId(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> stockConcurrencyService.decrease(1L, 1))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
			});
	}

	@DisplayName("재고가 부족하면 예외가 발생한다")
	@Test
	void decrease_whenOutOfStock_throwException() {
		// given
		Stock stock = createStock(1);
		given(stockRepository.findByProductId(1L)).willReturn(Optional.of(stock));

		// when & then
		assertThatThrownBy(() -> stockConcurrencyService.decrease(1L, 2))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.OUT_OF_STOCK);
			});
	}

	private Stock createStock(int quantity) {
		return Stock.builder()
			.quantity(quantity)
			.build();
	}

	private Stock createStock(Product product, int quantity) {
		return Stock.builder()
			.product(product)
			.quantity(quantity)
			.build();
	}

	private StockHistory createStockHistory(
		Long id,
		Stock stock,
		int quantityChange,
		StockAdjustmentReason reason,
		LocalDateTime createdAt
	) {
		StockHistory history = StockHistory.builder()
			.stock(stock)
			.quantityChange(quantityChange)
			.reason(reason)
			.adminMemberId(10L)
			.build();
		ReflectionTestUtils.setField(history, "id", id);
		ReflectionTestUtils.setField(history, "createdAt", createdAt);
		return history;
	}

	private Product createProduct(Long id, String name, int price) {
		Product product = Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
		ReflectionTestUtils.setField(product, "id", id);
		return product;
	}
}
