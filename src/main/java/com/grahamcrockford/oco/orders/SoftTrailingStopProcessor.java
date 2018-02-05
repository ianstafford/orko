package com.grahamcrockford.oco.orders;

import static java.math.RoundingMode.HALF_UP;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.meta.CurrencyPairMetaData;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.service.trade.TradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Singleton;
import com.grahamcrockford.oco.api.AdvancedOrderInfo;
import com.grahamcrockford.oco.api.AdvancedOrderProcessor;
import com.grahamcrockford.oco.core.ExchangeService;
import com.grahamcrockford.oco.core.AdvancedOrderEnqueuer;
import com.grahamcrockford.oco.core.AdvancedOrderIdGenerator;
import com.grahamcrockford.oco.core.TelegramService;
import com.grahamcrockford.oco.core.TradeServiceFactory;

@Singleton
public class SoftTrailingStopProcessor implements AdvancedOrderProcessor<SoftTrailingStop> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SoftTrailingStopProcessor.class);
  private static final ColumnLogger COLUMN_LOGGER = new ColumnLogger(LOGGER,
      LogColumn.builder().name("#").width(13).rightAligned(false),
      LogColumn.builder().name("Exchange").width(10).rightAligned(false),
      LogColumn.builder().name("Pair").width(10).rightAligned(false),
      LogColumn.builder().name("Operation").width(13).rightAligned(false),
      LogColumn.builder().name("Entry").width(13).rightAligned(true),
      LogColumn.builder().name("Stop").width(13).rightAligned(true),
      LogColumn.builder().name("Bid").width(13).rightAligned(true),
      LogColumn.builder().name("Last").width(13).rightAligned(true),
      LogColumn.builder().name("Ask").width(13).rightAligned(true)
  );
  private static final BigDecimal HUNDRED = new BigDecimal(100);

  private final TelegramService telegramService;
  private final TradeServiceFactory tradeServiceFactory;
  private final ExchangeService exchangeService;
  private final AdvancedOrderEnqueuer advancedOrderEnqueuer;
  private final AdvancedOrderIdGenerator advancedOrderIdGenerator;

  private final AtomicInteger logRowCount = new AtomicInteger();



  @Inject
  public SoftTrailingStopProcessor(final TelegramService telegramService,
                                   final TradeServiceFactory tradeServiceFactory,
                                   final ExchangeService exchangeService,
                                   final AdvancedOrderEnqueuer advancedOrderEnqueuer,
                                   final AdvancedOrderIdGenerator advancedOrderIdGenerator) {
    this.telegramService = telegramService;
    this.tradeServiceFactory = tradeServiceFactory;
    this.exchangeService = exchangeService;
    this.advancedOrderEnqueuer = advancedOrderEnqueuer;
    this.advancedOrderIdGenerator = advancedOrderIdGenerator;
  }

  @Override
  public void tick(SoftTrailingStop order, Ticker ticker) throws Exception {

    final AdvancedOrderInfo ex = order.basic();

    if (ticker.getAsk() == null) {
      LOGGER.warn("Market {}/{}/{} has no sellers!", ex.exchange(), ex.base(), ex.counter());
      telegramService.sendMessage(String.format("Market %s/%s/%s has no sellers!", ex.exchange(), ex.base(), ex.counter()));
      advancedOrderEnqueuer.enqueueAfterConfiguredDelay(order);
      return;
    }
    if (ticker.getBid() == null) {
      LOGGER.warn("Market {}/{}/{} has no buyers!", ex.exchange(), ex.base(), ex.counter());
      telegramService.sendMessage(String.format("Market %s/%s/%s has no buyers!", ex.exchange(), ex.base(), ex.counter()));
      advancedOrderEnqueuer.enqueueAfterConfiguredDelay(order);
      return;
    }

    final CurrencyPairMetaData currencyPairMetaData = exchangeService.get(ex.exchange())
      .getExchangeMetaData()
      .getCurrencyPairs()
      .get(ex.currencyPair());

    logStatus(order, ticker, currencyPairMetaData);

    // If we've hit the stop price, we're done
    if (ticker.getBid().compareTo(stopPrice(order, currencyPairMetaData)) <= 0) {

      // Sell up
      String xChangeOrderId = limitSell(order, ticker, limitPrice(order, currencyPairMetaData));

      // Spawn a new job to monitor the progress of the stop
      advancedOrderEnqueuer.enqueue(OrderStateNotifier.builder()
          .id(advancedOrderIdGenerator.next())
          .basic(order.basic())
          .description("Stop")
          .orderId(xChangeOrderId)
          .build());
      return;
    }

    if (ticker.getBid().compareTo(order.lastSyncPrice()) > 0 ) {
      order = order.toBuilder()
        .lastSyncPrice(ticker.getBid())
        .build();
    }

    advancedOrderEnqueuer.enqueueAfterConfiguredDelay(order);
  }

  private String limitSell(SoftTrailingStop trailingStop, Ticker ticker, BigDecimal limitPrice) throws IOException {
    final AdvancedOrderInfo ex = trailingStop.basic();

    LOGGER.info("| - Placing limit sell of [{} {}] at limit price [{} {}]", trailingStop.amount(), ex.base(), limitPrice, ex.counter());
    final TradeService tradeService = tradeServiceFactory.getForExchange(ex.exchange());
    final Date timestamp = ticker.getTimestamp() == null ? new Date() : ticker.getTimestamp();
    final LimitOrder order = new LimitOrder(Order.OrderType.ASK, trailingStop.amount(), ex.currencyPair(), null, timestamp, limitPrice);
    final String xChangeOrderId = tradeService.placeLimitOrder(order);

    LOGGER.info("| - Order [{}] placed.", xChangeOrderId);
    telegramService.sendMessage(String.format(
      "Bot [%d] on [%s/%s/%s] placed limit sell at [%s]",
      trailingStop.id(),
      ex.exchange(),
      ex.base(),
      ex.counter(),
      limitPrice
    ));

    return xChangeOrderId;
  }


  private void logStatus(final SoftTrailingStop trailingStop, final Ticker ticker, CurrencyPairMetaData currencyPairMetaData) {
    final AdvancedOrderInfo ex = trailingStop.basic();

    final int rowCount = logRowCount.getAndIncrement();
    if (rowCount == 0) {
      COLUMN_LOGGER.header();
    }
    if (rowCount == 25) {
      logRowCount.set(0);
    }
    COLUMN_LOGGER.line(
      trailingStop.id(),
      ex.exchange(),
      ex.pairName(),
      "Trailing stop",
      trailingStop.startPrice().setScale(currencyPairMetaData.getPriceScale(), HALF_UP),
      stopPrice(trailingStop, currencyPairMetaData),
      ticker.getBid().setScale(currencyPairMetaData.getPriceScale(), HALF_UP),
      ticker.getLast().setScale(currencyPairMetaData.getPriceScale(), HALF_UP),
      ticker.getAsk().setScale(currencyPairMetaData.getPriceScale(), HALF_UP)
    );
  }

  private BigDecimal stopPrice(SoftTrailingStop trailingStop, CurrencyPairMetaData currencyPairMetaData) {
    final BigDecimal stopPrice = trailingStop.lastSyncPrice()
        .multiply(BigDecimal.ONE.subtract(trailingStop.stopPercentage().divide(HUNDRED)));
    return stopPrice.setScale(currencyPairMetaData.getPriceScale(), RoundingMode.HALF_UP);
  }

  private BigDecimal limitPrice(SoftTrailingStop trailingStop, CurrencyPairMetaData currencyPairMetaData) {
    final BigDecimal limitPrice = trailingStop.startPrice()
        .multiply(BigDecimal.ONE.subtract(trailingStop.limitPercentage().divide(HUNDRED)));
    return limitPrice.setScale(currencyPairMetaData.getPriceScale(), RoundingMode.HALF_UP);
  }
}