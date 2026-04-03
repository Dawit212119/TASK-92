package com.civicworks.scheduler;

import com.civicworks.domain.entity.Bill;
import com.civicworks.domain.enums.BillStatus;
import com.civicworks.repository.BillRepository;
import com.civicworks.repository.LateFeeEventRepository;
import com.civicworks.service.BillingService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@DisallowConcurrentExecution
public class LateFeeJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(LateFeeJob.class);

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private LateFeeEventRepository lateFeeEventRepository;

    @Autowired
    private BillingService billingService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Executing LateFeeJob");

        // Find bills where due_date + 10 days <= now AND not PAID/CANCELLED AND no late fee yet
        LocalDate gracePeriodCutoff = LocalDate.now().minusDays(10);
        List<Bill> overdueBills = billRepository.findOverdueBills(gracePeriodCutoff,
                List.of(BillStatus.PAID, BillStatus.CANCELLED));

        int applied = 0;
        for (Bill bill : overdueBills) {
            if (bill.getStatus() == BillStatus.PAID || bill.getStatus() == BillStatus.CANCELLED) {
                continue;
            }
            if (lateFeeEventRepository.existsByBillId(bill.getId())) {
                continue;
            }
            try {
                billingService.applyLateFee(bill.getId(), bill.getVersion(), null);
                applied++;
            } catch (Exception e) {
                log.warn("Failed to apply late fee for bill {}: {}", bill.getId(), e.getMessage());
            }
        }

        log.info("LateFeeJob: applied late fees to {} bills", applied);
    }
}
