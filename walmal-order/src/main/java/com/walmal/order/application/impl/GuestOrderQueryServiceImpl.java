package com.walmal.order.application.impl;

import com.walmal.order.application.GuestOrderQueryService;
import com.walmal.order.infrastructure.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class GuestOrderQueryServiceImpl implements GuestOrderQueryService {

    private final OrderRepository orderRepository;

    public GuestOrderQueryServiceImpl(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findGuestEmailByOrderId(UUID orderId) {
        return orderRepository.findGuestEmailByOrderId(orderId);
    }
}
