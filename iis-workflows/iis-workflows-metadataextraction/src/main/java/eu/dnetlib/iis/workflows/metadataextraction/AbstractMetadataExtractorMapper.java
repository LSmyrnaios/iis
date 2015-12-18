package eu.dnetlib.iis.workflows.metadataextraction;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.avro.mapred.AvroKey;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.log4j.Logger;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;

import com.itextpdf.text.exceptions.InvalidPdfException;

import eu.dnetlib.iis.audit.schemas.Fault;
import eu.dnetlib.iis.common.WorkflowRuntimeParameters;
import eu.dnetlib.iis.common.fault.FaultUtils;
import eu.dnetlib.iis.core.javamapreduce.MultipleOutputs;
import eu.dnetlib.iis.metadataextraction.schemas.ExtractedDocumentMetadata;
import pl.edu.icm.cermine.ContentExtractor;
import pl.edu.icm.cermine.exception.AnalysisException;
import pl.edu.icm.cermine.exception.TransformationException;

/**
 * Abstract class containing shared code of metadata extraction.
 * 
 * @author mhorst
 *
 */
public abstract class AbstractMetadataExtractorMapper<T>
        extends Mapper<AvroKey<T>, NullWritable, NullWritable, NullWritable> {

    public static final String LOG_FAULT_PROCESSING_TIME_THRESHOLD_SECS = "log.fault.processing.time.threshold.secs";

    public static final String FAULT_CODE_PROCESSING_TIME_THRESHOLD_EXCEEDED = "ProcessingTimeThresholdExceeded";

    public static final String FAULT_SUPPLEMENTARY_DATA_PROCESSING_TIME = "processing_time";

    public static final String FAULT_SUPPLEMENTARY_DATA_URL = "url";

    public static final String IMPORT_CONTENT_CONNECTION_TIMEOUT = "import.content.connection.timeout";
    public static final String IMPORT_CONTENT_READ_TIMEOUT = "import.content.read.timeout";
    public static final String IMPORT_CONTENT_MAX_FILE_SIZE_MB = "import.content.max.file.size.mb";

    protected final Logger log = Logger.getLogger(AbstractMetadataExtractorMapper.class);

    /**
     * Flag indicating {@link AnalysisException} should cause interruption.
     */
    protected boolean analysisExceptionAsCritical = false;

    /**
     * Flag indicating any other {@link Exception} should cause interruption.
     */
    protected boolean otherExceptionAsCritical = false;

    /**
     * Multiple outputs.
     */
    protected MultipleOutputs mos = null;

    /**
     * Document metadata named output.
     */
    protected String namedOutputMeta;

    /**
     * Fault named output.
     */
    protected String namedOutputFault;

    /**
     * Progress log interval.
     */
    protected int progresLogInterval = 100;

    /**
     * Current progress.
     */
    protected int currentProgress = 0;

    /**
     * Interval time.
     */
    private long intervalTime = 0;

    /**
     * Maximum content size in MegaBytes.
     */
    protected long maxFileSizeKB = Long.MAX_VALUE;

    /**
     * Processing time threshold. When exceeded apropriate object will be
     * written to error datastore.
     */
    protected long processingTimeThreshold = Long.MAX_VALUE;

    /**
     * Set of object identifiers objects excluded from processing.
     */
    protected Set<String> excludedIds;

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.hadoop.mapreduce.Mapper#setup(org.apache.hadoop.mapreduce.
     * Mapper.Context)
     */
    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        namedOutputMeta = context.getConfiguration().get("output.meta");
        if (namedOutputMeta == null || namedOutputMeta.isEmpty()) {
            throw new RuntimeException("no named output provided for metadata");
        }
        namedOutputFault = context.getConfiguration().get("output.fault");
        if (namedOutputFault == null || namedOutputFault.isEmpty()) {
            throw new RuntimeException("no named output provided for fault");
        }

        String excludedIdsCSV = context.getConfiguration().get("excluded.ids");
        if (excludedIdsCSV != null && !excludedIdsCSV.trim().isEmpty()
                && !WorkflowRuntimeParameters.UNDEFINED_NONEMPTY_VALUE.equals(excludedIdsCSV)) {
            log.warn("got excluded ids: " + excludedIdsCSV);
            excludedIds = new HashSet<String>(Arrays.asList(StringUtils.split(excludedIdsCSV.trim(), ',')));
        } else {
            log.warn("got no excluded ids");
        }
        // handling maximum content size
        String maxFileSizeMBStr = context.getConfiguration().get(IMPORT_CONTENT_MAX_FILE_SIZE_MB);
        if (maxFileSizeMBStr != null && !maxFileSizeMBStr.trim().isEmpty()
                && !WorkflowRuntimeParameters.UNDEFINED_NONEMPTY_VALUE.equals(maxFileSizeMBStr)) {
            this.maxFileSizeKB = 1024l * Integer.valueOf(maxFileSizeMBStr);
        }

        // handling processing time threshold
        String processingTimeThresholdSecsStr = context.getConfiguration()
                .get(LOG_FAULT_PROCESSING_TIME_THRESHOLD_SECS);
        if (processingTimeThresholdSecsStr != null && !processingTimeThresholdSecsStr.trim().isEmpty()
                && !WorkflowRuntimeParameters.UNDEFINED_NONEMPTY_VALUE.equals(processingTimeThresholdSecsStr)) {
            this.processingTimeThreshold = 1000l * Integer.valueOf(processingTimeThresholdSecsStr);
        }

        mos = new MultipleOutputs(context);
        currentProgress = 0;
        intervalTime = System.currentTimeMillis();
    }

    /**
     * Processes content input stream. Closes stream at the end.
     * 
     * @param documentId
     * @param contentStream
     * @param contentLengthKB
     *            content length in KB
     * @param auditSupplementaryData
     *            additional data reqiured for auditing purposes
     * @throws IOException
     * @throws InterruptedException
     */
    protected void processStream(CharSequence documentId, InputStream contentStream, long contentLengthKB,
            Map<CharSequence, CharSequence> auditSupplementaryData) throws IOException, InterruptedException {
        try {
            currentProgress++;
            if (currentProgress > 0 && currentProgress % progresLogInterval == 0) {
                // FIXME switch back to debug when setting debug level on oozie
                log.warn("metadata extaction progress: " + currentProgress + ", time taken to process "
                        + progresLogInterval + " elements: " + ((System.currentTimeMillis() - intervalTime) / 1000)
                        + " secs");
                intervalTime = System.currentTimeMillis();
            }
            if (excludedIds != null && excludedIds.contains(documentId)) {
                log.warn("skipping processing for excluded id " + documentId);
            } else {
                // handling maximum content size
                if (contentLengthKB > maxFileSizeKB) {
                    log.warn("skipping processing for id " + documentId + " due to max file size limit=" + maxFileSizeKB
                            + " KB exceeded: " + contentLengthKB + " KB");
                    try {
                        // writing empty metadata
                        mos.write(namedOutputMeta,
                                new AvroKey<ExtractedDocumentMetadata>(NlmToDocumentWithBasicMetadataConverter
                                        .convertFull(documentId.toString(), null, null)));
                        return;
                    } catch (TransformationException e2) {
                        mos.close();
                        throw new RuntimeException(e2);
                    } catch (JDOMException e2) {
                        mos.close();
                        throw new RuntimeException(e2);
                    } finally {
                        if (contentStream != null) {
                            contentStream.close();
                        }
                    }
                }

                log.info("starting processing for id: " + documentId);
                long startTime = System.currentTimeMillis();
                ContentExtractor extractor = new ContentExtractor();
                try {
                    extractor.setPDF(contentStream);
                    try {
                        Element resultElem = extractor.getNLMContent();
                        Document doc = new Document(resultElem);
                        XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());
                        log.debug("got NLM content: \n" + outputter.outputString(resultElem));
                        String text = null;
                        try {
                            text = extractor.getRawFullText();
                        } catch (AnalysisException e) {
                            log.error("unable to extract plaintext, trying with metadata extraction...", e);
                        }
                        mos.write(namedOutputMeta, new AvroKey<ExtractedDocumentMetadata>(
                                NlmToDocumentWithBasicMetadataConverter.convertFull(documentId.toString(), doc, text)));
                    } catch (JDOMException e) {
                        mos.close();
                        throw new RuntimeException(e);
                    } catch (TransformationException e) {
                        mos.close();
                        throw new RuntimeException(e);
                    } catch (AnalysisException e) {
                        if (analysisExceptionAsCritical) {
                            mos.close();
                            throw new RuntimeException(e);
                        } else {
                            if (e.getCause() instanceof InvalidPdfException) {
                                log.error("Invalid PDF file", e);
                            } else {
                                log.error("got unexpected analysis exception, just logging", e);
                            }
                            try {
                                // writing empty result
                                mos.write(namedOutputMeta,
                                        new AvroKey<ExtractedDocumentMetadata>(NlmToDocumentWithBasicMetadataConverter
                                                .convertFull(documentId.toString(), null, null)));
                                // writing fault result
                                mos.write(namedOutputFault, new AvroKey<Fault>(
                                        FaultUtils.exceptionToFault(documentId, e, auditSupplementaryData)));
                            } catch (TransformationException e2) {
                                mos.close();
                                throw new RuntimeException(e2);
                            } catch (JDOMException e2) {
                                mos.close();
                                throw new RuntimeException(e2);
                            }
                        }
                    } catch (Exception e) {
                        if (otherExceptionAsCritical) {
                            mos.close();
                            throw new RuntimeException(e);
                        } else {
                            log.error("got unexpected exception, just logging", e);
                            try {
                                // writing empty result
                                mos.write(namedOutputMeta,
                                        new AvroKey<ExtractedDocumentMetadata>(NlmToDocumentWithBasicMetadataConverter
                                                .convertFull(documentId.toString(), null, null)));
                                // writing fault result
                                mos.write(namedOutputFault, new AvroKey<Fault>(
                                        FaultUtils.exceptionToFault(documentId, e, auditSupplementaryData)));
                            } catch (TransformationException e2) {
                                mos.close();
                                throw new RuntimeException(e2);
                            } catch (JDOMException e2) {
                                mos.close();
                                throw new RuntimeException(e2);
                            }
                        }
                    }
                } finally {
                    if (contentStream != null) {
                        contentStream.close();
                    }
                }
                long processingTime = System.currentTimeMillis() - startTime;
                if (processingTime > processingTimeThreshold) {
                    Map<CharSequence, CharSequence> supplementaryData = new HashMap<CharSequence, CharSequence>();
                    if (auditSupplementaryData != null) {
                        supplementaryData.putAll(auditSupplementaryData);
                    }
                    supplementaryData.put(FAULT_SUPPLEMENTARY_DATA_PROCESSING_TIME, String.valueOf(processingTime));
                    // writing fault result
                    mos.write(namedOutputFault,
                            new AvroKey<Fault>(Fault.newBuilder().setInputObjectId(documentId)
                                    .setTimestamp(System.currentTimeMillis())
                                    .setCode(FAULT_CODE_PROCESSING_TIME_THRESHOLD_EXCEEDED)
                                    .setSupplementaryData(supplementaryData).build()));
                }
                log.info("finished processing for id " + documentId + " in " + (processingTime / 1000) + " secs");
            }
        } catch (AnalysisException e) {
            mos.close();
            throw new RuntimeException(e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.hadoop.mapreduce.Mapper#cleanup(org.apache.hadoop.mapreduce.
     * Mapper.Context)
     */
    @Override
    public void cleanup(Context context) throws IOException, InterruptedException {
        mos.close();
    }

    /**
     * Sets flag indicating {@link AnalysisException} should cause interruption.
     * 
     * @param analysisExceptionAsCritical
     */
    public void setAnalysisExceptionAsCritical(boolean analysisExceptionAsCritical) {
        this.analysisExceptionAsCritical = analysisExceptionAsCritical;
    }

    /**
     * Sets flag indicating any other {@link Exception} should cause
     * interruption.
     * 
     * @param otherExceptionAsCritical
     */
    public void setOtherExceptionAsCritical(boolean otherExceptionAsCritical) {
        this.otherExceptionAsCritical = otherExceptionAsCritical;
    }

    public void setProgresLogInterval(int progresLogInterval) {
        this.progresLogInterval = progresLogInterval;
    }

}
