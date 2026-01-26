package com.commerce.stock.service;

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
import com.commerce.stock.domain.Stock;
import com.commerce.stock.exception.StockErrorCode;
import com.commerce.stock.exception.StockException;
import com.commerce.stock.repository.StockRepository;
import com.commerce.stock.service.request.StockDecreaseBatchServiceRequest;
import com.commerce.stock.service.response.StockDecreaseBatchResponse;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {

	@Mock
	private StockRepository stockRepository;

	@InjectMocks
	private StockService stockService;

	@DisplayName("재고가 존재하면 차감된다")
	@Test
	void decrease_whenStockExists_decreaseQuantity() {
		// given
		Stock stock = createStock(10);
		given(stockRepository.findByProductId(1L)).willReturn(Optional.of(stock));

		// when
		stockService.decrease(1L, 3);

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
		stockService.decreaseWithPessimisticLock(1L, 4);

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
		stockService.increaseWithPessimisticLock(1L, 3);

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

		StockDecreaseBatchServiceRequest request = StockDecreaseBatchServiceRequest.from(
			Map.of(1L, 1, 2L, 1)
		);

		given(stockRepository.findAllByProductIdInWithPessimisticLock(argThat(ids ->
			ids.containsAll(List.of(1L, 2L)) && ids.size() == 2
		)))
			.willReturn(List.of(stock1, stock2));

		// when
		stockService.decreaseBatchWithPessimisticLock(request);

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

		StockDecreaseBatchServiceRequest request = StockDecreaseBatchServiceRequest.builder()
			.quantitiesByProductId(Map.of(1L, 2, 2L, 1))
			.build();

		given(stockRepository.findAllByProductIdInWithPessimisticLock(argThat(ids ->
			ids.containsAll(List.of(1L, 2L)) && ids.size() == 2
		)))
			.willReturn(List.of(stock1, stock2));

		// when
		StockDecreaseBatchResponse response = stockService.decreaseBatchWithPessimisticLock(request);

		// then
		assertThat(stock1.getQuantity()).isEqualTo(8);
		assertThat(stock2.getQuantity()).isEqualTo(8);
		assertThat(response.getItemCount()).isEqualTo(2);
		assertThat(response.getTotalQuantity()).isEqualTo(3);
	}

	@DisplayName("비관적 락 조회 결과가 부족하면 예외가 발생한다")
	@Test
	void decreaseBatchWithPessimisticLock_whenStockMissing_throwException() {
		// given
		Product product1 = createProduct(1L, "product-1", 1000);
		Stock stock1 = createStock(product1, 10);

		StockDecreaseBatchServiceRequest request = StockDecreaseBatchServiceRequest.from(
			Map.of(1L, 1, 2L, 1)
		);

		given(stockRepository.findAllByProductIdInWithPessimisticLock(argThat(ids ->
			ids.containsAll(List.of(1L, 2L)) && ids.size() == 2
		)))
			.willReturn(List.of(stock1));

		// when & then
		assertThatThrownBy(() -> stockService.decreaseBatchWithPessimisticLock(request))
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
		assertThatThrownBy(() -> stockService.decrease(1L, 1))
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
		assertThatThrownBy(() -> stockService.decrease(1L, 2))
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

	private Product createProduct(Long id, String name, int price) {
		Product product = Product.builder()
			.name(name)
			.price(price)
			.build();
		ReflectionTestUtils.setField(product, "id", id);
		return product;
	}
}
