package eu.dnetlib.iis.common.report;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Lists;

import eu.dnetlib.iis.common.counter.PigCounters;
import eu.dnetlib.iis.common.schemas.ReportEntry;

/**
 * Resolver of {@link ReportEntry}s from {@link PigCounters} that uses {@link ReportPigCounterMapping}.
 * 
 * @author madryk
 */
public class ReportPigCountersResolver {

    //------------------------ LOGIC --------------------------
    
    /**
     * Resolve {@link ReportEntry}s from {@link PigCounters} using {@link ReportPigCounterMapping}s.
     * Only counters that are present in {@link ReportPigCounterMapping}s will be resolved.
     */
    public List<ReportEntry> resolveReportCounters(PigCounters pigCounters, List<ReportPigCounterMapping> reportPigCountersMappings) {
        
        List<ReportEntry> reportCounters = Lists.newArrayList();
        
        for (ReportPigCounterMapping counterMapping : reportPigCountersMappings) {
        
            String counterValue = extractCounterValue(pigCounters, counterMapping);
            
            ReportEntry reportEntry = ReportEntryFactory.createCounterReportEntry(counterMapping.getDestReportCounterName(), Long.valueOf(counterValue));
            
            reportCounters.add(reportEntry);
        }
        
        return reportCounters;
    }
    
    
    
    //------------------------ PRIVATE --------------------------

    private String extractCounterValue(PigCounters pigCounters, ReportPigCounterMapping counterMapping) {
        
        String counterValue;
        
        if (counterMapping.isRootLevelCounterMapping()) {
            
            counterValue = pigCounters.getRootLevelCounters().get(counterMapping.getSourcePigCounterName());
            
            if (StringUtils.isBlank(counterValue)) {
                throw new IllegalArgumentException("Couldn't find a root level counter with name: " + counterMapping.getSourcePigCounterName());
            }
            
            
        } else {
            
            String jobId = pigCounters.getJobIdByAlias(counterMapping.getSourcePigJobAlias());
            
            if (jobId == null) {
                throw new IllegalArgumentException("Non existent job alias: " + counterMapping.getSourcePigJobAlias());
            }
            
            counterValue = pigCounters.getJobCounters(jobId).getCounter(counterMapping.getSourcePigCounterName());
            
            if (StringUtils.isBlank(counterValue)) {
                throw new IllegalArgumentException("Couldn't find a job counter with name: " + counterMapping.getSourcePigCounterName() + ", job id: " + jobId);
            }
            
        }
        
        return counterValue;
    }
}
