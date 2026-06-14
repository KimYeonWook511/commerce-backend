package com.commerce.stock.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.stock.application.command.StockDecreaseBatchCommand;
import com.commerce.stock.application.result.StockDecreaseBatchResult;
import com.commerce.stock.domain.Stock;
import com.commerce.stock.domain.repository.StockRepository;
import com.commerce.stock.domain.exception.StockErrorCode;
import com.commerce.stock.domain.exception.StockException;

@ExtendWith(MockitoExtension.class)
class StockInventoryServiceTest {

	@Mock
	private StockRepository stockRepository;

	@InjectMocks
	private StockInventoryService stockInventoryService;

	@DisplayName("비관적 락으로 조회한 재고는 차감된다")
	@Test
	void decrease_whenStockExists_decreaseQuantity() {
		// given
		Stock stock = createStock(10);
		given(stockRepository.findByProductIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

		// when
		stockInventoryService.decrease(1L, 4);

		// then
		assertThat(stock.getQuantity()).isEqualTo(6);
	}

	@DisplayName("비관적 락으로 조회한 재고는 증가한다")
	@Test
	void increase_whenStockExists_increaseQuantity() {
		// given
		Stock stock = createStock(5);
		given(stockRepository.findByProductIdWithPessimisticLock(1L)).willReturn(Optional.of(stock));

		// when
		stockInventoryService.increase(1L, 3);

		// then
		assertThat(stock.getQuantity()).isEqualTo(8);
	}

	@DisplayName("비관적 락으로 여러 재고를 조회하면 모두 차감된다")
	@Test
	void decreaseBatch_whenStocksExist_decreaseQuantities() {
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
		stockInventoryService.decreaseBatch(command);

		// then
		assertThat(stock1.getQuantity()).isEqualTo(9);
		assertThat(stock2.getQuantity()).isEqualTo(8);
	}

	@DisplayName("비관적 락 배치 요청은 차감 후 요약 정보를 반환한다")
	@Test
	void decreaseBatch_whenRequest_returnSummary() {
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
		StockDecreaseBatchResult result = stockInventoryService.decreaseBatch(command);

		// then
		assertThat(stock1.getQuantity()).isEqualTo(8);
		assertThat(stock2.getQuantity()).isEqualTo(8);
		assertThat(result.getItemCount()).isEqualTo(2);
		assertThat(result.getTotalQuantity()).isEqualTo(3);
	}

	@DisplayName("비관적 락 조회 결과가 부족하면 예외가 발생한다")
	@Test
	void decreaseBatch_whenStockMissing_throwException() {
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
		assertThatThrownBy(() -> stockInventoryService.decreaseBatch(command))
			.isInstanceOf(StockException.class)
			.satisfies(exception -> {
				StockException stockException = (StockException) exception;
				assertThat(stockException.getErrorCode()).isEqualTo(StockErrorCode.STOCK_NOT_FOUND);
			});
	}

	private Stock createStock(int quantity) {
		return Stock.builder()
			.quantity(quantity)
			.build();
	}

	private Stock createStock(Product product, int quantity) {
		return Stock.builder()
			.productId(product.getId())
			.quantity(quantity)
			.build();
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
