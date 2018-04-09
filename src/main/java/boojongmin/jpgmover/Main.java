package boojongmin.jpgmover;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import lombok.Data;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class Main {
    static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger("Main");
    static Logger errLogger = LoggerFactory.getLogger("Main");


    static Map<String, List<MetaInfo>> result = new ConcurrentHashMap<>();
    static File source;
    static File destination;
    static Set<String> duplicateSet = ConcurrentHashMap.newKeySet();
    static ExecutorService pool = Executors.newFixedThreadPool(4);
    public static void main(String[] args) throws Exception {
        elapsedTimeChecker(() -> {
            checkInput(args);
            walkFiles(source);
            pool.shutdown();
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        }, "1. collect file info" );

        printReport();

        elapsedTimeChecker(() -> {
            moveJpgWithErrorHandling();
        }, "2. move files");
        log.info("complete");
    }

    private static void elapsedTimeChecker(FacadConsumer fn, String stageName) throws Exception {
        log.info(String.format("start -> %s\n", stageName));
        long startTime = System.currentTimeMillis();
        fn.run(stageName);
        long totalTime = System.currentTimeMillis() - startTime;
        log.info(String.format("end -> %s, elapsed time %d(ms)\n", stageName, totalTime));
    }


    private static void printReport() {
        log.info(String.format("total image count is %d\n", result.values().stream().map(x -> x.size()).reduce(0, Integer::sum)));
        log.info(String.format("total image size is %.2f Mb\n",
            result.values().stream().map(x ->
                x.stream()
                    .map(y -> y.getFile().length())
                    .reduce(0L, Long::sum)
            )
            .reduce(0L, Long::sum)
            .doubleValue() / (1000 * 1000)
        ));
    }

    private static void checkInput(String[] args) {
        if(args.length != 2) {
            log.info("[error] your input size is not 2.\nexit\n");
            System.exit(0);
        }
        source = new File(args[0]);
        destination = new File(args[1]);
        if(!source.exists()) {
            log.info("first argument is wrong. check image path");
        }

    }

    private static void moveJpgWithErrorHandling() {
        try {
            moveJpg();
        } catch (Exception e) {
            log.info(String.format("[error when move jpg] %s\n", e.getMessage()));
            errLogger.error(String.format("[error when move jpg] %s\n", e.getMessage()), e);
        }
    }

    private static void moveJpg() {
        if(!destination.exists()) {
            destination.mkdirs();
        }
        Path path = Paths.get(destination.toURI());

        for (String key : result.keySet()) {
            Path dir = path.resolve(key);
            File file = dir.toFile();
            if(!file.exists()) {
                file.mkdirs();
            }
            List<MetaInfo> infos = result.get(key);
            for (MetaInfo info : infos) {
                File target = info.getFile();
                try {
                    Files.move(Paths.get(target.toURI()), dir.resolve(target.getName()), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    errLogger.info(String.format("[copy error] folder: %s, file name: %s\n", dir.toFile().getAbsolutePath(), target.getName()));
                    errLogger.error("file copy error", e);
                }
            }
        }
    }

    private static void processMetaInfoWithHandleException(File file) {
        try {
            processMetaInfo(file);
        } catch (Exception e) {
            log.info(e.getMessage());
            log.info(String.format("error file path :  %s", file.getAbsolutePath()));
            errLogger.error(String.format("error file path :  %s", file.getAbsolutePath()));
            errLogger.error("processMetaInfoWithHandleException", e);
        }
    }

    private static void processMetaInfo(File file) throws ImageProcessingException, IOException, ParseException {
        Metadata metadata = ImageMetadataReader.readMetadata(file);
        Iterable<Directory> directories = metadata.getDirectories();
        SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
        MetaInfo metaInfo = new MetaInfo();
        metaInfo.setFile(file);
        for (Directory directory : directories) {
            for (Tag tag : directory.getTags()) {
                if(tag.getTagName().equals("Model")) {
                    metaInfo.setModel(tag.getDescription());
                } else if(tag.getTagName().equals("Date/Time")) {
                    Date created = inputFormat.parse(tag.getDescription());
                    metaInfo.setCreated(created);
                }
            }
        }
        if(metaInfo.getCreated() == null) {
            metaInfo.setCreated(new Date(0L));
        }
        String sha256Hex = DigestUtils.sha256Hex(Files.readAllBytes(file.toPath()));
        if(!duplicateSet.contains(sha256Hex)) {
            duplicateSet.add(sha256Hex);
            putInfo(metaInfo);
        }
    }


    private static void putInfo(MetaInfo metaInfo) {
        SimpleDateFormat outFormat = new SimpleDateFormat("yyyyMMdd");
        String key = metaInfo.getModel() + File.separator + outFormat.format(metaInfo.getCreated());
        result.computeIfAbsent(key, k -> new ArrayList<>()).add(metaInfo);
    }

    private static void walkFiles(File f) throws IOException {
        if(f.isDirectory()) {
            File[] files = f.listFiles();
            for (File file : files) {
                walkFiles(file);
            }
        } else {
            pool.execute(() -> {
                try {
                    Optional<String> contentTypeOpt = Optional.ofNullable(Files.probeContentType(f.toPath()));
                    contentTypeOpt.ifPresent(x -> {
                        if(x.startsWith("image")) processMetaInfoWithHandleException(f);
                    });
                } catch (Exception e) {
                    errLogger.error(String.format("[error when work files] %s\n", e.getMessage()));
                }
            });
        }
    }
}

@Data
class MetaInfo {
    private String model;
    private Date created;
    private File file;
}

@FunctionalInterface
interface FacadConsumer {
    void run() throws Exception;

    default void run(String stageName) {
        long startTime = System.currentTimeMillis();
        try {
            run();
        } catch(Exception e) {
            Main.log.info(String.format("[error %s] %s", stageName, e.getMessage()));
        }
        long endTime = System.currentTimeMillis();
        long totalTime =  endTime - startTime;
        Main.log.info(String.format("[elapsed time] %s : %d(ms)\n", stageName, totalTime));
    }
}
