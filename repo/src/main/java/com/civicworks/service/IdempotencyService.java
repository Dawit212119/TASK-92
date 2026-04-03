package com.civicworks.service;

import com.civicworks.domain.entity.IdempotencyRecord;
import com.civicworks.repository.IdempotencyRecordRepository;
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
    public Optional<IdempotencyRecord> findExisting(UUID userId, String key) {
        return repository.findByUserIdAndIdempotencyKey(userId, key);
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
