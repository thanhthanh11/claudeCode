package com.bank.account.service;

import com.bank.common.dto.ApiResponse;
import com.bank.common.dto.CustomerInfoResponse;
import com.bank.common.exception.ForbiddenException;
import com.bank.common.exception.RemoteServiceException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.resilience.RemoteCallGuard;
import com.bank.common.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gọi REST đồng bộ tới customer-service (qua Eureka/LoadBalancer), chuyển tiếp JWT của người dùng.
 * Dùng khi cần dữ liệu mới nhất (vd. trạng thái KYC lúc mở tài khoản), không qua cache.
 * Có circuit breaker: customer-service lỗi liên tục thì từ chối ngay (503) thay vì chờ timeout.
 */
@Slf4j
@Service
public class CustomerClientService {

    private static final String CUSTOMER_URL = "http://customer-service/api/customers/{id}";
    private static final String CIRCUIT_BREAKER = "customer-service-rest";

    private final RestClient restClient;
    private final RemoteCallGuard callGuard;

    public CustomerClientService(@LoadBalanced RestClient.Builder restClientBuilder,
                                 ObjectProvider<RemoteCallGuard> callGuard) {
        this.restClient = restClientBuilder.build();
        this.callGuard = callGuard.getIfAvailable(() -> RemoteCallGuard.NOOP);
    }

    public CustomerInfoResponse getCustomer(Long customerId) {
        String token = SecurityUtils.currentToken()
                .orElseThrow(() -> new IllegalStateException("Không có token để gọi customer-service"));
        return callGuard.call(CIRCUIT_BREAKER, () -> fetchCustomer(customerId, token));
    }

    private CustomerInfoResponse fetchCustomer(Long customerId, String token) {
        try {
            ApiResponse<CustomerInfoResponse> response = restClient.get()
                    .uri(CUSTOMER_URL, customerId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null || response.data() == null) {
                throw new ResourceNotFoundException("CUSTOMER_NOT_FOUND", "Không tìm thấy khách hàng #" + customerId);
            }
            return response.data();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("CUSTOMER_NOT_FOUND", "Không tìm thấy khách hàng #" + customerId);
        } catch (HttpClientErrorException.Forbidden e) {
            throw new ForbiddenException("Không có quyền truy cập khách hàng #" + customerId);
        } catch (RestClientException e) {
            log.error("Gọi customer-service thất bại: {}", e.getMessage());
            throw new RemoteServiceException(HttpStatus.SERVICE_UNAVAILABLE, "CUSTOMER_SERVICE_UNAVAILABLE",
                    "Không kết nối được customer-service");
        }
    }
}
