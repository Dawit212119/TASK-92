package com.civicworks.service;

import com.civicworks.domain.entity.IdempotencyRecord;
import com.civicworks.exception.BusinessException;
import com.civicworks.repository.IdempotencyRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;

    public IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> findExisting(UUID userId, String key, String actionType) {
        return repository.findByUserIdAndIdempotencyKeyAndActionType(userId, key, actionType);
    }

    @Transactional(readOnly = true)
    public void checkForActionConflict(UUID userId, String key, String actionType) {
        if (repository.existsByUserIdAndIdempotencyKeyAndActionTypeNot(userId, key, actionType)) {
            throw new BusinessException(
                    "Idempotency key already used for a different action type",
                    HttpStatus.CONFLICT, "IDEMPOTENCY_ACTION_CONFLICT");
        }
    }

    @Transactional
    public IdempotencyRecord save(UUID userId, String key, String actionType, int status, String body) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setUserId(userId);
        record.setIdempotencyKey(key);
        record.setActionType(actionType);
        record.setResponseStatus(status);
        record.setResponseBody(body);
        return repository.save(record);
    }
}
