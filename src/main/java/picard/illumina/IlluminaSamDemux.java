/*
 * The MIT License
 *
 * Copyright (c) 2011 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package picard.illumina;

import htsjdk.samtools.*;
import htsjdk.samtools.metrics.MetricsFile;
import htsjdk.samtools.util.*;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import picard.PicardException;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.programgroups.BaseCallingProgramGroup;
import picard.illumina.parser.*;
import picard.util.*;

import java.io.Closeable;
import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IlluminaBamDemux demultiplexes one unaligned BAM file per lane into a set on unaligned bam files per sample.
 * <p/>
 * In this application, barcode data is read from the BC field per each read (of pairs of reads in case of PE reads).
 */
@CommandLineProgramProperties(
        summary = IlluminaSamDemux.USAGE_SUMMARY,
        oneLineSummary = IlluminaSamDemux.USAGE_SUMMARY,
        programGroup = BaseCallingProgramGroup.class
)
@DocumentedFeature
public class IlluminaSamDemux extends CommandLineProgram {

    static final String USAGE_SUMMARY = " Demultiplexes one unaligned BAM file per lane into a set on unaligned bam files per sample.";
    static final String SAMPLE_NAME_COL = "SAMPLE_NAME";
    static final String LIBRARY_NAME_COL = "LIBRARY_NAME";
    static final String BARCODE1_COL = "BARCODE_1";
    static final String BARCODE2_COL = "BARCODE_2";


    @Argument(doc = "The input BAM file to be demultiplexed. ", shortName = "I")
    public File INPUT;

    @Argument(doc = "The output directory for bam files for each barcode. Default is the same folder as the input", optional = true)
    public File OUTPUT_DIR;

    @Argument(doc = "The prefix for bam or sam file when you want to split output by barcodes. Default is the RG id found in the unaligned bam", optional = true)
    public String OUTPUT_PREFIX;

    @Argument(doc = "The extension name for split file when you want to split output by barcodes: bam or sam")
    public String OUTPUT_FORMAT = "bam";

    @Argument(shortName = "BC_SEQ", doc = "The tag name used to store barcode read in bam records")
    public String BARCODE_TAG_NAME = "BC";

    @Argument(shortName = "BC_QUAL", doc = "Tag name for barcode quality.")
    public String BARCODE_QUALITY_TAG_NAME = "QT";

    @Argument(doc = "Tab-separated file for creating all output BAMs for a lane with single IlluminaBamDemux " +
            "invocation.  The columns are "+SAMPLE_NAME_COL+", "+LIBRARY_NAME_COL+", "+BARCODE1_COL+", "+BARCODE2_COL+" (optional)." +
            "The second (i5) barcode is not considered, even when present, if BARCODE_2 column is not present, or BARCODE_2 is set to N ")
    public File LIBRARY_PARAMS;

    @Argument(doc = "Per-barcode and per-lane metrics written to this file.", shortName = StandardOptionDefinitions.METRICS_FILE_SHORT_NAME)
    public File METRICS_FILE;

    @Argument(doc = "Maximum mismatches for a barcode to be considered a match.")
    public int MAX_MISMATCHES = 1;

    @Argument(doc = "Minimum difference between number of mismatches in the best and second best barcodes for a barcode to be considered a match.")
    public int MIN_MISMATCH_DELTA = 1;

    @Argument(doc = "Maximum allowable number of no-calls in a barcode read before it is considered unmatchable.")
    public int MAX_NO_CALLS = 2;

    @Argument(shortName = "Q", doc = "Minimum base quality. Any barcode bases falling below this quality will be considered a mismatch even in the bases match.")
    public int MINIMUM_BASE_QUALITY = 0;

    @Argument(doc = ReadStructure.PARAMETER_DOC, shortName = "RS", optional = true)
    public String READ_STRUCTURE;

    @Argument(doc = "Which adapters to look for in the read.")
    public List<IlluminaUtil.IlluminaAdapterPair> ADAPTERS_TO_CHECK = new ArrayList<>(
            Arrays.asList(IlluminaUtil.IlluminaAdapterPair.INDEXED,
                    IlluminaUtil.IlluminaAdapterPair.DUAL_INDEXED,
                    IlluminaUtil.IlluminaAdapterPair.NEXTERA_V2,
                    IlluminaUtil.IlluminaAdapterPair.FLUIDIGM));

    @Argument(doc = "For specifying adapters other than standard Illumina", optional = true)
    public String FIVE_PRIME_ADAPTER;

    @Argument(doc = "For specifying adapters other than standard Illumina", optional = true)
    public String THREE_PRIME_ADAPTER;

    @Argument(doc = "The tag to use to store any molecular indexes.  If more than one molecular index is found, they will be concatenated and stored here.", optional = true)
    public String MOLECULAR_INDEX_TAG = "RX";

    @Argument(doc = "The tag to use to store any molecular index base qualities.  If more than one molecular index is found, their qualities will be concatenated and stored here " +
            "(.i.e. the number of \"M\" operators in the READ_STRUCTURE)", optional = true)
    public String MOLECULAR_INDEX_BASE_QUALITY_TAG = "QX";

    @Argument(doc = "The list of tags to store each molecular index.  The number of tags should match the number of molecular indexes.", optional = true)
    public List<String> TAG_PER_MOLECULAR_INDEX;

    @Argument(doc = "The tag to use to store any cellular indexes.  If more than one cellular index is found, they will be concatenated and stored here.", optional = true)
    public String CELL_INDEX_TAG = "CB";

    @Argument(doc = "The tag to use to store any cellular index base qualities.  If more than one cellular index is found, their qualities will be concatenated and stored here " +
            "(.i.e. the number of \"C\" operators in the READ_STRUCTURE)", optional = true)
    public String CELL_INDEX_BASE_QUALITY_TAG = "CY";

    @Argument(doc = "Number of threads used for matching)", optional = true)
    public Integer MATCHING_THREADS = 1;


    private ReadStructure readStructure;
    private Map<String, ExtractIlluminaBarcodes.BarcodeMetric> barcodes = new HashMap<>();
    private ExtractIlluminaBarcodes.BarcodeMetric noMatchMetric;
    private SamReader inputReader;
    private static final Log log = Log.getInstance(IlluminaSamDemux.class);
    private  List<AdapterPair> adapters = new ArrayList<>(ADAPTERS_TO_CHECK);
    private BlockingQueue<Future<List<BarcodeMatchResult>>> matchJobs = new LinkedBlockingQueue<>(MATCHING_THREADS*2);
    private int batchSize = 100000;

    /**
     * Put any custom command-line validation in an override of this method.
     * clp is initialized at this point and can be used to print usage and access args.
     * Any options set by command-line parser can be validated.
     *
     * @return null if command line is valid.  If command line is invalid, returns an array of error message
     * to be written to the appropriate place.
     */
    @Override
    protected String[] customCommandLineValidation() {
        final ArrayList<String> messages = new ArrayList<>();

        try {
            IOUtil.assertFileIsReadable(INPUT);
        }catch (Exception e){
            messages.add("INPUT file "+INPUT.getAbsolutePath()+" is not readable");
        }

        if (OUTPUT_DIR==null) OUTPUT_DIR = INPUT.getParentFile();

        try {
            IOUtil.assertDirectoryIsWritable(OUTPUT_DIR);
        }catch (Exception e){
            messages.add("OUTPUT_DIR "+OUTPUT_DIR.getAbsolutePath()+" is not writable");
        }

        OUTPUT_FORMAT = OUTPUT_FORMAT.toLowerCase();
        if (!"bam".equalsIgnoreCase(OUTPUT_FORMAT) && !"sam".equalsIgnoreCase(OUTPUT_FORMAT)){
            messages.add("Unrecognized OUTPUT_FORMAT: " + OUTPUT_FORMAT + ". Must be bam or sam");
        }

        try {
            IOUtil.assertFileIsReadable(LIBRARY_PARAMS);
        } catch (Exception e) {
            messages.add("LIBRARY_PARAMS file " + LIBRARY_PARAMS.getAbsolutePath() + " is not readable");
        }

        try {
            IOUtil.assertFileIsWritable(METRICS_FILE);
        } catch (Exception e) {
            messages.add("METRICS_FILE " + METRICS_FILE.getAbsolutePath() + " is not writable");
        }

        if ((FIVE_PRIME_ADAPTER == null) != (THREE_PRIME_ADAPTER == null)) {
            messages.add("THREE_PRIME_ADAPTER and FIVE_PRIME_ADAPTER must either both be null or both be set.");
        }

        if (messages.isEmpty()) {
            return null;
        }
        return messages.toArray(new String[messages.size()]);
    }

    /**
     * Prepares loggers, initiates garbage collection thread, parses arguments and initialized variables appropriately/
     */
    private void initialize() {

        inputReader = SamReaderFactory.makeDefault().open(INPUT);

        if (READ_STRUCTURE==null){
            SAMFileHeader header = inputReader.getFileHeader();
            for (String comment: header.getComments()){
                if (comment.toUpperCase().contains("READ_STRUCTURE")){
                    READ_STRUCTURE = comment.split("=")[1];
                    break;
                }
            }
        }

        if (READ_STRUCTURE!=null){
            readStructure = new ReadStructure(READ_STRUCTURE);
        }else {
            throw new PicardException("Could not determine READ_STRUCTURE nor from command line, nor from input file");
        }

        adapters = new ArrayList<>(ADAPTERS_TO_CHECK);
        if (FIVE_PRIME_ADAPTER != null && THREE_PRIME_ADAPTER != null) {
            adapters.add(new CustomAdapterPair(FIVE_PRIME_ADAPTER, THREE_PRIME_ADAPTER));
        }

        parseBarcodeFile();

        matchJobs = new LinkedBlockingQueue<>(MATCHING_THREADS);

    }

    @Override
    protected int doWork() {


        ReadsWriter writer = null;
        try {
            initialize();
            final BarcodeMatcher matcher = new BarcodeMatcher();
            writer = new ReadsWriter();

            Thread writerThread = new Thread(writer);
            writerThread.start();

            Iterator<SAMRecord> iterator = inputReader.iterator();

            ThreadPoolExecutorWithExceptions matchingExecutor = new ThreadPoolExecutorWithExceptions(MATCHING_THREADS);
            log.info(String.format("Executor was started with %d threads",MATCHING_THREADS));


            List<ReadToMatch> batchToProcess = new LinkedList<>();


            long iterTime = 0;
            long queueWaitTime = 0;

            int i = 0;

            long startTime =System.currentTimeMillis();
            while (iterator.hasNext()) {
                SAMRecord currentRecord = iterator.next();
                SAMRecord nextRecord= null;
                if (currentRecord.getReadPairedFlag()){
                    if (!currentRecord.getFirstOfPairFlag()){
                        throw new PicardException("Record is marked as paired end but could not find first of pair "+currentRecord.getReadName());
                    }

                    nextRecord = iterator.next();
                    if (nextRecord==null ||
                            !nextRecord.getReadPairedFlag() ||
                            !nextRecord.getSecondOfPairFlag() ||
                            !nextRecord.getReadName().equals(currentRecord.getReadName())){
                        throw new PicardException("Record is marked as paired end but could not find second of pair "+currentRecord.getReadName());
                    }

                }

                batchToProcess.add(new ReadToMatch(currentRecord,nextRecord));


                iterTime += System.currentTimeMillis()-startTime;

                startTime = System.currentTimeMillis();
                if (batchToProcess.size() == batchSize) {
                    matchJobs.put(matchingExecutor.submit(
                            new BarcodeMatchJob(batchToProcess,matcher))
                    );
                    batchToProcess = new LinkedList<>();
                }
                queueWaitTime += System.currentTimeMillis()-startTime;

                i+=1;
                if (i %1000000 == 0){
                    log.info(String.format("Read %d reads. Last 1000000. IterTime: %d, Queue Time: %d",i,iterTime,queueWaitTime));
                    iterTime=0;
                    queueWaitTime=0;
                }
                startTime = System.currentTimeMillis();
            }


            writer.setDone(true);

            matchingExecutor.shutdown();
            ThreadPoolExecutorUtil.awaitThreadPoolTermination("Matching executor", matchingExecutor, Duration.ofMinutes(5));

            // if there was an exception reading then initiate an immediate shutdown.
            if (matchingExecutor.exception != null) {
                throw new PicardException("Matching executor had exceptions.", matchingExecutor.exception);
            }

            ExtractIlluminaBarcodes.finalizeMetrics(barcodes,noMatchMetric);

            final MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric, Integer> metrics = getMetricsFile();
            for (final ExtractIlluminaBarcodes.BarcodeMetric barcodeMetric : barcodes.values()) {
                metrics.addMetric(barcodeMetric);
            }
            metrics.addMetric(noMatchMetric);
            metrics.write(METRICS_FILE);

            return 0;
        } catch (InterruptedException e){
            throw new PicardException("Interrupted" ,e);
        } finally{
            CloserUtil.close(inputReader);
            CloserUtil.close(writer);
        }

    }

    public static void main(final String[] args) {
        System.exit(new IlluminaSamDemux().instanceMain(args));
    }

    private void parseBarcodeFile() {
        TabbedTextFileWithHeaderParser barcodesParser = new TabbedTextFileWithHeaderParser(LIBRARY_PARAMS);
        try {

            if (!barcodesParser.hasColumn(BARCODE1_COL)) {
                throw new IllegalArgumentException("The LIBRARY_PARAMS file is missing the " + BARCODE1_COL + " column");
            }

            if (!barcodesParser.hasColumn(SAMPLE_NAME_COL)) {
                throw new IllegalArgumentException("The LIBRARY_PARAMS file is missing the " + SAMPLE_NAME_COL + " column");
            }


            int numBarcodes = readStructure.sampleBarcodes.length();

            if (numBarcodes == 0) {
                throw new IllegalArgumentException("There is no barcode read in the Read Structure " + READ_STRUCTURE);
            } else if (numBarcodes > 2) {
                log.warn("More than two barcode reads in the run, but only the first two are supported");
                numBarcodes = 2;
            }

            int i = 0;

            for (final TabbedTextFileWithHeaderParser.Row row : barcodesParser) {
                i++;

                String sampleName = row.getField(SAMPLE_NAME_COL).trim();
                if (sampleName.isEmpty()) {
                    throw new IllegalArgumentException("Sample name not found in row " + i + " of LIBRARY_PARAMS.");
                }

                String libraryName = barcodesParser.hasColumn(LIBRARY_NAME_COL) ? row.getField(LIBRARY_NAME_COL).trim() : sampleName;
                if (libraryName.isEmpty()) libraryName = sampleName;

                String bc1 = parseBarcode(row.getField(BARCODE1_COL), sampleName, 0);
                if (bc1 == null) {
                    throw new IllegalArgumentException("Invalid " + BARCODE1_COL + " found for sample " + sampleName + " of LIBRARY_PARAMS. ");
                }

                String bc2 = ( barcodesParser.hasColumn(BARCODE2_COL)) ? parseBarcode(row.getField(BARCODE2_COL), sampleName, 1) : null;

                if (numBarcodes == 1 && bc2 != null) {
                    log.warn("Sample  " + sampleName + " has two barcodes in LIBRARY_PARAMS, but only one barcode was read in run. The second barcode will be ignored. ");
                    bc2 = null;
                } else if (numBarcodes == 2 && bc2 == null) {
                    log.warn("The read is dual indexed, but sample  " + sampleName + " has only one valid barcode in LIBRARY_PARAMS. Barcode 2 will be ignored. ");
                }


                String[] barcodesArray = (bc2==null) ? new String[] {bc1} : new String[]  {bc1,bc2};

                ExtractIlluminaBarcodes.BarcodeMetric barcodeMetric = new ExtractIlluminaBarcodes.BarcodeMetric(
                        sampleName,
                        libraryName,
                        IlluminaUtil.barcodeSeqsToString(barcodesArray),
                        barcodesArray
                );

                for (ExtractIlluminaBarcodes.BarcodeMetric otherBarcode: barcodes.values()) {
                    byte[][] firstBarcodeBytes;
                    byte[][] otherBarcodeBytes;

                    //ensure that the barcode with fewer reads is first
                    if (barcodeMetric.barcodeBytes.length <= otherBarcode.barcodeBytes.length){
                        firstBarcodeBytes = barcodeMetric.barcodeBytes;
                        otherBarcodeBytes = otherBarcode.barcodeBytes;
                    }else {
                        otherBarcodeBytes = barcodeMetric.barcodeBytes;
                        firstBarcodeBytes = otherBarcode.barcodeBytes;
                    }


                    int mismatches = ExtractIlluminaBarcodes.PerTileBarcodeExtractor.countMismatches(firstBarcodeBytes, otherBarcodeBytes, null, 0);
                    if (mismatches<=MAX_MISMATCHES) {
                        throw new IllegalArgumentException("Error in LIBRARY_PARAMS: Barcode "+barcodeMetric.BARCODE_WITHOUT_DELIMITER+" for sample "+barcodeMetric.BARCODE_NAME+
                        "collides with barcode "+otherBarcode.BARCODE_WITHOUT_DELIMITER+" of sample "+otherBarcode.BARCODE_NAME+
                        ". There are "+mismatches+" mismatches which are less or equal than "+MAX_MISMATCHES+" MAX_MISMATCHES");
                    }
                }

                barcodes.put(barcodeMetric.BARCODE_WITHOUT_DELIMITER,barcodeMetric);
            }


            final String[] noMatchBarcode = new String[readStructure.sampleBarcodes.length()];
            int index = 0;
            for (final ReadDescriptor d : readStructure.descriptors) {
                if (d.type == ReadType.Barcode) {
                    noMatchBarcode[index++] = StringUtil.repeatCharNTimes('N', d.length);
                }
            }

            noMatchMetric = new ExtractIlluminaBarcodes.BarcodeMetric(null, null, IlluminaUtil.barcodeSeqsToString(noMatchBarcode), noMatchBarcode);
        }finally {
            barcodesParser.close();
        }
    }

    private String parseBarcode(String barcodeString, String sampleName, int barcodeIndex){

        String bc = barcodeString.trim().toUpperCase();
        if (bc==null || bc.isEmpty() || !bc.matches("[ATCG]+")){
            return null;
        }

        int readLength = readStructure.sampleBarcodes.get(barcodeIndex).length;
        if (readLength < bc.length()) {
            log.warn("Read length for barcode is "+readLength+"." +
                    " Barcode"+(barcodeIndex+1)+" in LIBRARY_PARAMS "+bc+" for sample "+sampleName+" has length "+bc.length()+". Trying to trim the barcode");
            bc = bc.substring(0,readLength);
        }else if (readLength > bc.length()){
            log.warn("Read length for barcode is "+readLength+"." +
                    " Barcode"+(barcodeIndex+1)+" in LIBRARY_PARAMS "+bc+" for sample "+sampleName+" has length "+bc.length()+"." +
                    " Only the first "+bc.length()+" bases of the read will be considered");
        }

        return bc;
    }

    private class BarcodeMatchResult {
        private final SAMRecord[] outputRecords;
        private final ExtractIlluminaBarcodes.PerTileBarcodeExtractor.BarcodeMatch barcodeMatch;

        public BarcodeMatchResult(SAMRecord[] outputRecords, ExtractIlluminaBarcodes.PerTileBarcodeExtractor.BarcodeMatch barcodeMatch) {
            this.outputRecords = outputRecords;
            this.barcodeMatch = barcodeMatch;
        }
    }

    private class ReadToMatch {
        private final SAMRecord firstRecord;
        private final SAMRecord secondRecord;

        public ReadToMatch(SAMRecord firstRecord, SAMRecord secondRecord) {
            this.firstRecord = firstRecord;
            this.secondRecord = secondRecord;
        }
    }

    private class BarcodeMatchJob implements Callable<List<BarcodeMatchResult>> {
        private final List<ReadToMatch> readsToMatch;
        private final  BarcodeMatcher barcodeMatcher;

        public BarcodeMatchJob(List<ReadToMatch> readsToMatch, BarcodeMatcher matcher) {
            this.readsToMatch = readsToMatch;
            this.barcodeMatcher = matcher;
        }

        @Override
        public List<BarcodeMatchResult> call() {

            List<BarcodeMatchResult> results = new LinkedList<>();
            for (ReadToMatch read: readsToMatch){
                results.add(barcodeMatcher.processRecord(read));
            }
            return results;
        }
    }


    private class ReadsWriter implements Closeable,Runnable {
        private final Map<String, SAMFileWriter> writers = new HashMap<>();
        private final SAMFileWriter noMatchWriter;
        private boolean isDone = false;


        public ReadsWriter() {

            for (ExtractIlluminaBarcodes.BarcodeMetric barcodeMetric: barcodes.values()){
                String fileName = OUTPUT_PREFIX+getRgSuffix(barcodeMetric.BARCODE_WITHOUT_DELIMITER)+"."+OUTPUT_FORMAT;
                SAMFileWriter fileWriter = buildSamFileWriter(new File(OUTPUT_DIR,fileName),inputReader.getFileHeader(), barcodeMetric.BARCODE_WITHOUT_DELIMITER);
                writers.put(barcodeMetric.BARCODE_WITHOUT_DELIMITER, fileWriter);
            }

            String fileName = OUTPUT_PREFIX+getRgSuffix(null)+"."+OUTPUT_FORMAT;
            noMatchWriter = buildSamFileWriter(new File(OUTPUT_DIR,fileName),inputReader.getFileHeader(), null);
        }

        @Override
        public void run() {
            log.info("Writer was started");

            AtomicLong i = new AtomicLong();
            AtomicLong writeTime = new AtomicLong();
            AtomicLong waitTime = new AtomicLong();

            long startWait = System.currentTimeMillis();
            while (!isDone) {
                try {
                    Future<List<BarcodeMatchResult>> resultFuture = matchJobs.poll(500,TimeUnit.MILLISECONDS);
                    if (resultFuture!=null){
                        long lastWaitTime = waitTime.addAndGet(System.currentTimeMillis() - startWait);

                        List<BarcodeMatchResult> results = resultFuture.get();

                        long startWrite = System.currentTimeMillis();
                        long processed;
                        for (BarcodeMatchResult result: results) {
                            SAMFileWriter writer;
                            if (result.barcodeMatch.matched) {
                                writer = writers.get(result.barcodeMatch.barcode);
                            } else {
                                writer = noMatchWriter;
                            }
                            for (SAMRecord record : result.outputRecords) {
                                writer.addAlignment(record);
                            }

                            long lastWriteTime = writeTime.addAndGet(System.currentTimeMillis()- startWrite);
                            if (( processed= i.incrementAndGet()) % 1000000 == 0){
                                synchronized (this){
                                    log.info(String.format("Wrote %d reads. Last 1000000. Write Time: %d, Wait Time: %d." ,processed, lastWriteTime, lastWaitTime));
                                    writeTime.set(0);
                                    waitTime.set(0);
                                }
                            }

                            startWrite = System.currentTimeMillis();
                        }

                        startWait = System.currentTimeMillis();
                    }
                }catch (InterruptedException e){
                    log.info("Writer was interrupted");
                    break;
                }catch (ExecutionException e2){
                    throw new PicardException("Error while processing records ",e2);
                }

            }
        }

        @Override
        public void close(){
            for (SAMFileWriter writer: writers.values()){
                CloserUtil.close(writer);
            }
            CloserUtil.close(noMatchWriter);
        }


        public void setDone(boolean done) {
            isDone = done;
        }

        private SAMFileWriter buildSamFileWriter(final File output, SAMFileHeader previousHeader, String barcode) {
            IOUtil.assertFileIsWritable(output);

            final SAMFileHeader header = new SAMFileHeader();
            header.setSortOrder(SAMFileHeader.SortOrder.queryname);

            String previousProgram =null;
            for (SAMProgramRecord programRecord: previousHeader.getProgramRecords()) {
                header.addProgramRecord(programRecord);
                previousProgram = programRecord.getProgramGroupId();
            }

            SAMProgramRecord thisProgram = buildThisProgramHeaderInfo(previousProgram);
            header.addProgramRecord(thisProgram);

            String rgSuffix = getRgSuffix(barcode);

            for (SAMReadGroupRecord previousRg: previousHeader.getReadGroups()){
                String rgId = previousRg.getId() + rgSuffix;
                final SAMReadGroupRecord rg = new SAMReadGroupRecord(rgId, previousRg);
                if (barcode!=null && barcodes.containsKey(barcode)){
                    String sample = barcodes.get(barcode).BARCODE_NAME;
                    String library = barcodes.get(barcode).LIBRARY_NAME;
                    rg.setSample(sample);
                    rg.setLibrary(library);
                }
                rg.setProgramGroup(thisProgram.getProgramGroupId());
                rg.setPlatformUnit(previousRg.getPlatformUnit()+rgSuffix);
                header.addReadGroup(rg);
            }

            String comment = "READ_STRUCTURE="+readStructure.toString();
            header.addComment(comment);

            return new SAMFileWriterFactory().makeSAMOrBAMWriter(header, true, output);
        }


    }

    private class BarcodeMatcher {

        private AtomicLong matchTime = new AtomicLong();
        private AtomicLong processedRecords = new AtomicLong();

        private SamToSamMapper samMapper = new SamToSamMapper(readStructure, adapters)
                .withBarcodeTag(BARCODE_TAG_NAME)
                .withBarcodeQualityTag(BARCODE_QUALITY_TAG_NAME)
                .withMolecularIndexTag(MOLECULAR_INDEX_TAG)
                .withMolecularIndexQualityTag(MOLECULAR_INDEX_BASE_QUALITY_TAG)
                .withTagPerMolecularIndex(TAG_PER_MOLECULAR_INDEX)
                .withCellIndexTag(CELL_INDEX_TAG)
                .withCellIndexQualityTag(CELL_INDEX_BASE_QUALITY_TAG);


        public BarcodeMatchResult processRecord(ReadToMatch recordToMatch) {
            //log.info(String.format("processing record %d ",processedRecords.get()));
            long startTime = System.currentTimeMillis();

            IlluminaBasecallsToSam.SAMRecordsForCluster records = samMapper.convertClusterToOutputRecord(recordToMatch.firstRecord, recordToMatch.secondRecord);

            SAMRecord firstMappedRecord = records.records[0];

            ExtractIlluminaBarcodes.PerTileBarcodeExtractor.BarcodeMatch barcodeMatch = new ExtractIlluminaBarcodes.PerTileBarcodeExtractor.BarcodeMatch();
            boolean passingFilter = !firstMappedRecord.getReadFailsVendorQualityCheckFlag();

            if (firstMappedRecord.hasAttribute(BARCODE_TAG_NAME)) {
                String barcode = firstMappedRecord.getAttribute(BARCODE_TAG_NAME).toString();
                String barcodeQuality = firstMappedRecord.getAttribute(BARCODE_QUALITY_TAG_NAME).toString();

                int numBarcodesReads = readStructure.sampleBarcodes.length();
                byte[][] barcodesReads = new byte[numBarcodesReads][];
                byte[][] barcodesQualities = new byte[numBarcodesReads][];
                int startIndex = 0;
                for (int i = 0; i < numBarcodesReads; i++) {
                    ReadDescriptor barcodeDesc = readStructure.sampleBarcodes.get(i);
                    barcodesReads[i] = htsjdk.samtools.util.StringUtil.stringToBytes(barcode.substring(startIndex, startIndex + barcodeDesc.length));
                    barcodesQualities[i] = SAMUtils.fastqToPhred(barcodeQuality.substring(startIndex, startIndex + barcodeDesc.length));
                    startIndex = startIndex + barcodeDesc.length;
                }

                barcodeMatch = ExtractIlluminaBarcodes.PerTileBarcodeExtractor.findBestBarcodeAndUpdateMetrics(
                        barcodesReads,
                        barcodesQualities,
                        passingFilter,
                        barcodes,
                        noMatchMetric,
                        MAX_NO_CALLS,
                        MAX_MISMATCHES,
                        MIN_MISMATCH_DELTA,
                        MINIMUM_BASE_QUALITY
                );

            } else {
                ++noMatchMetric.READS;
                if (passingFilter) {
                    ++noMatchMetric.PF_READS;
                }
            }

            String rgSuffix = getRgSuffix((barcodeMatch.matched) ? barcodeMatch.barcode : null);

            for (SAMRecord record : records.records) {
                record.setReadName(record.getReadName() + rgSuffix);
                Object readGroup = record.getAttribute(SAMTag.RG.name());
                if (readGroup != null) {
                    record.setAttribute(SAMTag.RG.name(), readGroup.toString() + rgSuffix);
                }
            }

            long currentMatchTime = matchTime.addAndGet(System.currentTimeMillis() - startTime);

            long processed;
            if ((processed = processedRecords.incrementAndGet()) % 1000000 == 0) {
                synchronized (this) {
                    log.info(
                            String.format(
                                    "Matched %d reads. Last 1000000 took %d ms (CPU time)",
                                    processed,
                                    currentMatchTime
                            )
                    );
                    matchTime.set(0);
                }
            }

            return new BarcodeMatchResult(records.records, barcodeMatch);
        }

    }

    private SAMProgramRecord buildThisProgramHeaderInfo(String previousProgramId){
        SAMProgramRecord result = new SAMProgramRecord(this.getClass().getSimpleName());
        result.setProgramName(this.getClass().getSimpleName());
        result.setProgramVersion(this.getVersion());
        result.setCommandLine(this.getCommandLine());
        result.setAttribute("DS","Demultiplex unaligned BAM or SAM file");

        if (previousProgramId!=null) {
            result.setPreviousProgramGroupId(previousProgramId);
        }

        return result;
    }

    private String getRgSuffix(String barcode) {
        if (barcode == null) {
            return "#0";
        } else {
            if (barcodes.containsKey(barcode)) {
                return "#" + barcodes.get(barcode).BARCODE_NAME;
            } else {
                return "";
            }
        }

    }





}
